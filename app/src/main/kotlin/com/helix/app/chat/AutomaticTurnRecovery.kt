package com.helix.app.chat

import com.helix.app.engine.AutomaticRecoveryPolicy
import com.helix.app.engine.TurnRuntimeRecordCodec
import com.helix.app.engine.TurnRuntimeSnapshot
import com.helix.core.model.Clock
import com.helix.core.storage.HelixStorage
import kotlinx.coroutines.CancellationException

/** Submission-mutex owner calls this after executor reconciliation. One durable successor per parent. */
internal class AutomaticTurnRecovery(
    private val storage: HelixStorage,
    private val clock: Clock,
    private val start: suspend (String, TurnRuntimeSnapshot) -> String?,
    private val finish: (String, String, String?) -> Unit,
    private val unavailable: () -> String,
) {
    @Suppress("ReturnCount") // Distinct durable admission and replay guards must fail closed.
    suspend fun recover(turnId: String) {
        val turn = storage.turns.resolve(turnId)
        if (AutomaticRecoveryPolicy.isInspection(turn)) {
            if (turn.state !in setOf("COMPLETED", "FAILED", "CANCELLED", "INTERRUPTED", "NEEDS_REVIEW")) return
            val notice = if (turn.state == "COMPLETED") null else unavailable()
            finish(turn.id, "RECOVERY_INSPECTION_FINISHED", notice)
            finish(requireNotNull(turn.recoveryFromTurnId), "RECOVERY_INSPECTION_FINISHED", null)
            return
        }
        if (!AutomaticRecoveryPolicy.eligible(turn)) return
        if (storage.turns
                .listBySession(turn.sessionId)
                .lastOrNull()
                ?.id != turnId
        ) {
            return
        }
        val id = AutomaticRecoveryPolicy.requestId(turnId)
        if (storage.turns.resolveByClientRequestId(id) != null) return
        val claimed = claim(turn.sessionId, id)
        if (!claimed) {
            finish(turnId, "RECOVERY_INTERRUPTED", unavailable())
            return
        }
        val started =
            try {
                storage.turnRuntimeRecords.find(turnId)?.let { start(turnId, TurnRuntimeRecordCodec.decode(it)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        if (started == null && storage.turns.resolveByClientRequestId(id) == null) {
            finish(turnId, "RECOVERY_UNAVAILABLE", unavailable())
        }
    }

    private fun claim(
        sessionId: String,
        id: String,
    ): Boolean {
        var claimed = false
        storage.withTransaction {
            if (storage.auditEvents.listByCorrelation(sessionId).none { it.id == id }) {
                storage.auditEvents.append(
                    id,
                    sessionId,
                    "recovery.claimed",
                    "SYSTEM",
                    "{}",
                    clock.now().toEpochMilli(),
                )
                claimed = true
            }
        }
        return claimed
    }
}
