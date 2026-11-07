package com.helix.app.infrastructure.agent

import com.helix.core.agent.RecoveryInspectionSnapshot
import com.helix.core.storage.HelixStorage
import com.helix.feature.conversation.application.recovery.TurnRecoveryClaim
import com.helix.feature.conversation.application.recovery.TurnRecoveryStore

/** Admission/Stop share the executor gate. A claim and original binding are read in one Room snapshot. */
internal class RoomTurnRecoveryStore(
    private val storage: HelixStorage,
    private val executionGate: Any,
    private val finishTask: (String, String, Boolean) -> Unit,
) : TurnRecoveryStore {
    override fun claim(
        turnId: String,
        at: Long,
    ): TurnRecoveryClaim =
        command {
            val turn = storage.turns.find(turnId) ?: return@command TurnRecoveryClaim.Unavailable
            val session = storage.sessions.find(turn.sessionId) ?: return@command TurnRecoveryClaim.Unavailable
            if (session.archivedAt != null) return@command TurnRecoveryClaim.Unavailable
            if (AutomaticRecoveryPolicy.isInspection(turn)) {
                return@command if (turn.state in TERMINAL) {
                    TurnRecoveryClaim.InspectionFinished(turn.state != "COMPLETED")
                } else {
                    TurnRecoveryClaim.Unavailable
                }
            }
            if (!AutomaticRecoveryPolicy.eligible(turn) ||
                storage.turns
                    .listBySession(turn.sessionId)
                    .lastOrNull()
                    ?.id != turn.id || hasSuccessor(turnId)
            ) {
                return@command TurnRecoveryClaim.Unavailable
            }
            val id = AutomaticRecoveryPolicy.requestId(turnId)
            if (storage.auditEvents.find(id) != null) return@command TurnRecoveryClaim.AlreadyClaimed
            storage.auditEvents.append(id, turn.sessionId, "recovery.claimed", "SYSTEM", "{}", at)
            val snapshot =
                storage.turnRuntimeRecords.find(turnId)?.let { record ->
                    try {
                        TurnRuntimeRecordCodec.decode(record)
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                }
            TurnRecoveryClaim.Claimed(
                snapshot?.let {
                    RecoveryInspectionSnapshot(it.providerId, it.modelId, it.providerSnapshot, it.control)
                },
            )
        }

    override fun hasSuccessor(parentTurnId: String): Boolean =
        storage.turns.resolveByClientRequestId(AutomaticRecoveryPolicy.requestId(parentTurnId)) != null

    override fun finish(
        turnId: String,
        reason: String,
        inspectionFailed: Boolean,
    ) = command {
        val turn = storage.turns.find(turnId) ?: return@command
        if (reason in setOf("RECOVERY_INTERRUPTED", "RECOVERY_UNAVAILABLE") && hasSuccessor(turnId)) return@command
        finishTask(turnId, reason, inspectionFailed)
        if (AutomaticRecoveryPolicy.isInspection(turn)) {
            finishTask(requireNotNull(turn.recoveryFromTurnId), reason, inspectionFailed)
        }
    }

    private fun <T> command(block: () -> T): T =
        synchronized(executionGate) {
            var result: T? = null
            storage.withTransaction { result = block() }
            @Suppress("UNCHECKED_CAST")
            result as T
        }

    private companion object {
        val TERMINAL = setOf("COMPLETED", "FAILED", "CANCELLED", "INTERRUPTED", "NEEDS_REVIEW")
    }
}
