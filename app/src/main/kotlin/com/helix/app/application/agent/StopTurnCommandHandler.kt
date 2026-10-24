package com.helix.app.application.agent

import com.helix.app.application.agent.GoalContinuationDriver
import com.helix.app.engine.EngineCancelDecision
import com.helix.app.engine.TurnEngine
import com.helix.app.engine.TurnObservation
import com.helix.core.agent.CancelResult
import com.helix.core.model.Clock
import com.helix.core.model.TurnId
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Application cancellation commands; callers identify the original Turn, never a UI selection. */
internal fun interface TurnStopCommands {
    suspend fun cancel(turnId: TurnId): CancelResult
}

/** Cancels pending authorization for this Turn, without granting or executing any tool. */
internal fun interface PendingTurnApprovalCancellation {
    fun cancel(turnId: String)
}

/**
 * Owns stop admission and live signalling. Room commits before signals are sent; accepted stop
 * never claims executor exit, terminal settlement or rollback of an external effect.
 */
internal class StopTurnCommandHandler(
    private val storage: HelixStorage,
    private val clock: Clock,
    private val turnEngine: TurnEngine,
    private val turnGate: Any,
    private val goalContinuation: GoalContinuationDriver,
    private val goalUserRequests: MutableMap<String, *>,
    private val pendingApprovals: PendingTurnApprovalCancellation,
) : TurnStopCommands {
    override suspend fun cancel(turnId: TurnId): CancelResult = stop(turnId.value, reason = "USER_STOP")

    suspend fun stopTask(
        turnId: String,
        pause: Boolean,
        systemReason: String?,
    ): Unit =
        withContext(Dispatchers.IO + NonCancellable) {
            require(systemReason == null || systemReason in SYSTEM_REASONS)
            if (pause) {
                if (storage.goalTurnBindings.byTurn(turnId) == null) return@withContext
                if (!storage.turns.requestPause(turnId, clock.now().toEpochMilli())) {
                    return@withContext
                }
            }
            if (systemReason != null) turnEngine.requestSystemStop(turnId, systemReason)
            stop(turnId, "USER_STOP")
            Unit
        }

    suspend fun stopScheduled(
        turnId: String,
        sessionId: String,
        reason: String,
    ): CancelResult {
        require(reason in SCHEDULED_REASONS)
        return stop(turnId, reason, expectedSessionId = sessionId)
    }

    private suspend fun stop(
        turnId: String,
        reason: String,
        expectedSessionId: String? = null,
    ): CancelResult =
        withContext(Dispatchers.IO + NonCancellable) {
            synchronized(turnGate) {
                val task = storage.turns.find(turnId) ?: return@synchronized CancelResult.NotFound
                check(expectedSessionId == null || task.sessionId == expectedSessionId) { "turn/session mismatch" }
                val owner = turnEngine.liveExecution.active(task.sessionId)
                val handoffOwner = goalContinuation.handoffOwner(task.sessionId)
                val ownsHandoff = owner == null && handoffOwner == turnId
                val ownsDelivery = owner?.turnId == turnId || ownsHandoff
                if (ownsDelivery || (owner == null && handoffOwner == null)) {
                    goalContinuation.disarmTurn(task.sessionId, turnId)
                }
                goalUserRequests.remove(turnId)
                val active = owner?.takeIf { it.turnId == turnId }
                when (
                    val decision =
                        turnEngine.requestCancel(
                            turnId = turnId,
                            sessionId = task.sessionId,
                            hasLiveDriver = active != null,
                            parkTerminalDelivery = ownsDelivery,
                            reason = reason,
                        )
                ) {
                    EngineCancelDecision.NotFound -> {
                        CancelResult.NotFound
                    }

                    is EngineCancelDecision.AlreadyTerminal -> {
                        if (ownsDelivery) goalContinuation.disarm(task.sessionId)
                        CancelResult.AlreadyTerminal(decision.phase)
                    }

                    EngineCancelDecision.ReviewRequired -> {
                        CancelResult.ReviewRequired
                    }

                    EngineCancelDecision.SignalLive -> {
                        goalContinuation.disarm(task.sessionId)
                        turnEngine.publishObservation(TurnObservation(turnId, TurnState.CANCELLING))
                        active?.signalCancel()
                        pendingApprovals.cancel(turnId)
                        active?.job?.cancel()
                        CancelResult.StopAccepted
                    }
                }
            }
        }

    private companion object {
        val SYSTEM_REASONS = setOf("FGS_START_REJECTED", "FGS_TIMEOUT", "FGS_SERVICE_LOST")
        val SCHEDULED_REASONS = setOf("SCHEDULED_USER_STOP", "SCHEDULED_TIMEOUT", "SCHEDULED_INTERRUPTED")
    }
}
