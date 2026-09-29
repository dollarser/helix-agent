package com.helix.app.chat

import android.util.Log
import com.helix.app.agent.AutomaticGoalContinuation
import com.helix.core.agent.SubmitTurnCommand
import com.helix.core.model.Clock
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.repository.SessionInputDelivery
import com.helix.core.storage.repository.SessionInputRecord
import com.helix.core.storage.repository.SessionInputState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Session-level next-work arbiter.
 *
 * A Turn is already durably settled before [prepareAfterTerminal] runs. This component owns only
 * the safe-boundary decision about what a Session may run next: queued user input wins over an
 * automatic Goal continuation. Room remains the durable truth and [GoalContinuationDriver]
 * remains process-local activation/handoff state.
 */
@Suppress("LongParameterList") // Application wiring; one Session next-work owner with narrow callbacks.
internal class SessionWorkScheduler(
    private val storage: HelixStorage,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val submissionGate: Mutex,
    private val turnGate: Any,
    private val hasLiveTurn: (String) -> Boolean,
    private val goalContinuation: GoalContinuationDriver,
    private val consumeQueueInput: suspend (SessionInputRecord) -> Unit,
    private val submitContinuation: suspend (SubmitTurnCommand) -> Unit,
    private val refreshProjection: () -> Unit,
    private val recoverInputs: suspend (String) -> Unit = {},
) {
    /**
     * Called after durable terminal settlement but before the live owner is released.
     * Reserves the handoff so no transient idle window can admit automatic work ahead of a user
     * queue item. Returns whether the Session should be drained after owner release.
     */
    fun prepareAfterTerminal(
        sessionId: String,
        turnId: String,
        state: TurnState,
    ): Boolean =
        synchronized(turnGate) {
            val turn = storage.turns.resolve(turnId)
            storage.sessionInputs
                .listPending(sessionId)
                .filter {
                    it.delivery == SessionInputDelivery.STEER &&
                        it.expectedTurnId == turnId &&
                        it.state == SessionInputState.PENDING
                }.forEach {
                    storage.sessionInputs.markNeedsAttention(
                        it.inputId,
                        it.revision,
                        "STEER_TARGET_FINISHED",
                        clock.now().toEpochMilli(),
                    )
                }

            val boundGoal = storage.goalTurnBindings.byTurn(turnId) != null
            if ((!boundGoal && state != TurnState.COMPLETED) ||
                !AutomaticGoalContinuation.accepts(state.name, turn.errorCode, turn.pauseRequestedAt)
            ) {
                goalContinuation.disarm(sessionId)
                return@synchronized state != TurnState.CANCELLED && turn.pauseRequestedAt == null
            }

            val queue = storage.sessionInputs.headQueue(sessionId)
            if (queue?.state == SessionInputState.PENDING) {
                goalContinuation.reserveUserHandoff(sessionId, turnId)
            } else if (storage.sessionInputs.listPending(sessionId).isEmpty()) {
                goalContinuation.reserveEligibleHandoff(sessionId, turnId)
            }
            true
        }

    /** Review/unknown execution stops revoke automatic continuation for this Session. */
    fun disarm(sessionId: String) {
        synchronized(turnGate) { goalContinuation.disarm(sessionId) }
    }

    /**
     * Wake the Session after an owner-release or queue mutation. All wakes serialize through the
     * same submission gate; re-reading Room/live ownership makes duplicate wakes harmless.
     */
    @Suppress("TooGenericExceptionCaught") // A committed input must be parked on any delivery failure.
    fun requestDrain(
        sessionId: String,
        handoffTurnId: String? = null,
        recoveryAttempt: Int = 0,
    ) {
        scope.launch {
            submissionGate.withLock {
                var releaseHandoff = handoffTurnId
                try {
                    if (synchronized(turnGate) { hasLiveTurn(sessionId) }) return@withLock
                    if (stopped(sessionId)) return@withLock
                    recoverInputs(sessionId)
                    val input =
                        synchronized(turnGate) {
                            if (hasLiveTurn(sessionId)) return@withLock
                            releaseHandoff = goalContinuation.handoffOwner(sessionId) ?: releaseHandoff
                            storage.sessionInputs.headQueue(sessionId)
                        }
                    if (input != null) {
                        if (input.state == SessionInputState.PENDING) consumeQueueInput(input)
                    } else {
                        val next =
                            synchronized(turnGate) {
                                if (storage.sessionInputs.listPending(sessionId).isEmpty()) {
                                    goalContinuation.resumeEligible(sessionId)
                                } else {
                                    null
                                }
                            }
                        if (next != null) {
                            if (releaseHandoff == null) releaseHandoff = next.goalContinuation?.previousTurnId
                            submitContinuation(next)
                        }
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    synchronized(turnGate) {
                        storage.sessionInputs.parkSessionInputs(
                            sessionId,
                            "INPUT_DELIVERY_FAILED",
                            clock.now().toEpochMilli(),
                        )
                        goalContinuation.disarm(sessionId)
                    }
                    Log.e(TAG, "Session input drain failed", error)
                } finally {
                    releaseHandoff?.let { synchronized(turnGate) { goalContinuation.finishHandoff(sessionId, it) } }
                    refreshProjection()
                    retryDrainIfNeeded(sessionId, recoveryAttempt)
                }
            }
        }
    }

    private fun retryDrainIfNeeded(
        sessionId: String,
        attempt: Int,
    ) {
        if (attempt < 2 && needsAnotherDrain(sessionId)) requestDrain(sessionId, recoveryAttempt = attempt + 1)
    }

    private fun stopped(sessionId: String): Boolean {
        val latest = storage.turns.listBySession(sessionId).lastOrNull()
        return latest?.state == "CANCELLED" || latest?.pauseRequestedAt != null || latest?.errorCode == "USER_STOP"
    }

    private fun needsAnotherDrain(sessionId: String): Boolean =
        synchronized(turnGate) {
            val turn = storage.turns.listBySession(sessionId).lastOrNull()
            val input = storage.sessionInputs.headQueue(sessionId)
            !hasLiveTurn(sessionId) && turn?.state != "CANCELLED" && turn?.pauseRequestedAt == null &&
                turn?.errorCode != "USER_STOP" && input?.state == SessionInputState.NEEDS_ATTENTION &&
                AutomaticInputRecovery.recoverable(input.blockedReason)
        }

    private companion object {
        const val TAG = "SessionWorkScheduler"
    }
}
