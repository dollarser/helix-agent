package com.helix.app.chat

import com.helix.core.model.Clock
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.repository.SessionInputDelivery
import com.helix.core.storage.repository.SessionInputRecord
import com.helix.core.storage.repository.SessionInputState

/** Runs under the submission mutex. Reuses accepted bytes and identities, never manufactures new input. */
internal class AutomaticInputRecovery(
    private val storage: HelixStorage,
    private val clock: Clock,
    private val revalidate: suspend (SessionInputRecord) -> Boolean,
    private val failureText: () -> String,
) {
    suspend fun recover(sessionId: String) {
        val latest = storage.turns.listBySession(sessionId).lastOrNull()
        if (latest?.state == "CANCELLED" || latest?.pauseRequestedAt != null || latest?.errorCode == "USER_STOP") return
        storage.sessionInputs.listPending(sessionId).forEach { candidate ->
            if (candidate.state == SessionInputState.NEEDS_ATTENTION && recoverable(candidate.blockedReason)) {
                recoverOne(candidate)
            }
        }
    }

    private suspend fun recoverOne(candidate: SessionInputRecord) {
        val claimed = claim(candidate)
        if (!claimed) {
            finish(candidate, "INPUT_RECOVERY_INTERRUPTED")
            return
        }
        if (candidate.delivery == SessionInputDelivery.STEER) {
            storage.sessionInputs.requeueAfterTargetFinished(candidate.inputId, candidate.revision, now())
        }
        val input = storage.sessionInputs.get(candidate.inputId) ?: return
        if (revalidate(input)) {
            if (!storage.sessionInputs.resumePending(input.inputId, input.revision, now())) {
                storage.sessionInputs.get(input.inputId)?.let { finish(it, "INPUT_RECOVERY_UNAVAILABLE") }
            }
        } else {
            storage.sessionInputs.get(input.inputId)?.let { finish(it, "INPUT_REVALIDATION_FAILED") }
        }
    }

    private fun claim(input: SessionInputRecord): Boolean {
        var claimed = false
        storage.withTransaction {
            val id = "input-recovery:${input.inputId}"
            if (storage.auditEvents.listByCorrelation(input.sessionId).none { it.id == id }) {
                storage.auditEvents.append(id, input.sessionId, "input.recovery_started", "SYSTEM", "{}", now())
                claimed = true
            }
        }
        return claimed
    }

    private fun finish(
        input: SessionInputRecord,
        reason: String,
    ) {
        storage.withTransaction {
            if (storage.sessionInputs.failPending(input.inputId, input.revision, reason, now())) {
                storage.messages.append(
                    "input-ended:${input.inputId}",
                    input.sessionId,
                    null,
                    "SYSTEM",
                    "RECOVERY_NOTICE",
                    failureText(),
                )
            }
        }
    }

    private fun now() = clock.now().toEpochMilli()

    companion object {
        fun recoverable(reason: String?): Boolean =
            reason in
                setOf(
                    "PROCESS_INTERRUPTED",
                    "STEER_TARGET_FINISHED",
                    "INPUT_DELIVERY_FAILED",
                    "INPUT_REVALIDATION_FAILED",
                    "INPUT_CONFIGURATION_CHANGED",
                    "INPUT_ADMISSION_FAILED",
                    "TURN_NOT_COMPLETED",
                    "TURN_NEEDS_REVIEW",
                    "SESSION_NEEDS_ATTENTION",
                )
    }
}
