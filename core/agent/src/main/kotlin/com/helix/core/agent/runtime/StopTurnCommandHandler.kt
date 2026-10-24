package com.helix.core.agent.runtime

import com.helix.core.agent.CancelResult
import com.helix.core.agent.turn.TurnCancellationReceipt
import com.helix.core.model.TurnId
import com.helix.core.model.TurnState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Application cancellation commands; callers identify the original Turn, never a UI selection. */
fun interface TurnStopCommands {
    suspend fun cancel(turnId: TurnId): CancelResult
}

/** Cancels pending authorization for this Turn, without granting or executing any tool. */
fun interface PendingTurnApprovalCancellation {
    fun cancel(turnId: String)
}

/** Revokes only the targeted Turn's live delivery activation; it never creates a successor. */
interface TurnDeliveryControl {
    fun handoffOwner(sessionId: String): String?

    fun disarmTurn(
        sessionId: String,
        turnId: String,
    )

    fun disarm(sessionId: String)

    fun forgetUserRequests(turnId: String)
}

/**
 * Owns stop admission and live signalling. The durable store commits before signals are sent; accepted stop
 * never claims executor exit, terminal settlement or rollback of an external effect.
 */
class StopTurnCommandHandler(
    private val turnEngine: TurnEngine,
    private val delivery: TurnDeliveryControl,
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
                if (!turnEngine.store.requestGoalPause(turnId)) {
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
            synchronized(turnEngine.executionGate) {
                val task = turnEngine.store.findTurn(turnId) ?: return@synchronized CancelResult.NotFound
                check(expectedSessionId == null || task.sessionId == expectedSessionId) { "turn/session mismatch" }
                val owner = turnEngine.liveExecution.active(task.sessionId)
                val handoffOwner = delivery.handoffOwner(task.sessionId)
                val ownsHandoff = owner == null && handoffOwner == turnId
                val ownsDelivery = owner?.turnId == turnId || ownsHandoff
                if (ownsDelivery || (owner == null && handoffOwner == null)) {
                    delivery.disarmTurn(task.sessionId, turnId)
                }
                delivery.forgetUserRequests(turnId)
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
                    TurnCancellationReceipt.NotFound -> {
                        CancelResult.NotFound
                    }

                    is TurnCancellationReceipt.AlreadyTerminal -> {
                        if (ownsDelivery) delivery.disarm(task.sessionId)
                        CancelResult.AlreadyTerminal(decision.phase)
                    }

                    TurnCancellationReceipt.ReviewRequired -> {
                        CancelResult.ReviewRequired
                    }

                    TurnCancellationReceipt.SignalLive -> {
                        delivery.disarm(task.sessionId)
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
