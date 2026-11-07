package com.helix.feature.conversation.application.recovery

import com.helix.core.agent.SubmitTurnCommand
import com.helix.core.model.Clock
import com.helix.core.model.TurnState
import com.helix.core.model.input.InputRecoveryPolicy
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
 * automatic Goal continuation. Room remains the durable truth and [GoalContinuationHandoff]
 * remains process-local activation/handoff state.
 */
@Suppress("LongParameterList") // Application wiring; one Session next-work owner with narrow callbacks.
class SessionWorkScheduler(
    private val store: SessionWorkStore,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val submissionGate: Mutex,
    private val turnGate: Any,
    private val hasLiveTurn: (String) -> Boolean,
    private val goalContinuation: GoalContinuationHandoff,
    private val consumeQueueInput: suspend (QueuedSessionWork) -> Unit,
    private val submitContinuation: suspend (SubmitTurnCommand) -> Unit,
    private val refreshProjection: () -> Unit,
    private val reportFailure: (Exception) -> Unit,
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
            val facts = store.terminalFacts(sessionId, turnId)
            val boundGoal = facts.goalBound
            if ((!boundGoal && state != TurnState.COMPLETED) ||
                !facts.continuationAllowed
            ) {
                goalContinuation.disarm(sessionId)
                return@synchronized !facts.userStopped
            }

            val snapshot = store.snapshot(sessionId)
            val queue = snapshot.queue
            if (queue?.pending == true) {
                goalContinuation.reserveUserHandoff(sessionId, turnId)
            } else if (!snapshot.hasPending) {
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
     * One try/finally owns queue priority, handoff release and retry after any delivery failure.
     */
    @Suppress("TooGenericExceptionCaught", "CyclomaticComplexMethod")
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
                            if (hasLiveTurn(sessionId) || stopped(sessionId)) return@withLock
                            releaseHandoff = goalContinuation.handoffOwner(sessionId) ?: releaseHandoff
                            store.snapshot(sessionId).queue
                        }
                    if (input != null) {
                        if (input.pending) consumeQueueInput(input)
                    } else {
                        val next =
                            synchronized(turnGate) {
                                val latest = store.snapshot(sessionId)
                                if (!latest.stopped && !latest.hasPending) {
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
                        store.parkAfterFailure(sessionId, clock.now().toEpochMilli())
                        goalContinuation.disarm(sessionId)
                    }
                    reportFailure(error)
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

    private fun stopped(sessionId: String): Boolean = store.snapshot(sessionId).stopped

    private fun needsAnotherDrain(sessionId: String): Boolean =
        synchronized(turnGate) {
            val snapshot = store.snapshot(sessionId)
            val input = snapshot.queue
            !hasLiveTurn(sessionId) && !snapshot.stopped && input != null && !input.pending &&
                InputRecoveryPolicy.recoverable(input.blockedReason)
        }
}
