package com.helix.core.storage

import com.helix.core.model.input.InputRecoveryClaim
import com.helix.core.model.input.InputRecoveryPolicy
import com.helix.core.model.input.InputRecoveryStore
import com.helix.core.model.input.RecoveryInputCandidate
import com.helix.core.model.input.SessionInputDelivery
import com.helix.core.storage.repository.SessionInputRecord
import com.helix.core.storage.repository.SessionInputState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Stop and recovery serialize on the same executor gate; Room owns every durable decision. */
class RoomInputRecoveryStore(
    private val storage: HelixStorage,
    private val executionGate: Any,
) : InputRecoveryStore {
    override fun candidates(sessionId: String): List<RecoveryInputCandidate> =
        command {
            if (stopped(sessionId)) {
                emptyList()
            } else {
                storage.sessionInputs
                    .listPending(sessionId)
                    .filter {
                        it.state == SessionInputState.NEEDS_ATTENTION &&
                            InputRecoveryPolicy.recoverable(it.blockedReason)
                    }.map { it.candidate() }
            }
        }

    override fun claim(
        input: RecoveryInputCandidate,
        at: Long,
    ): InputRecoveryClaim =
        command {
            if (!unchanged(input) || stopped(input.sessionId)) {
                return@command InputRecoveryClaim.Unavailable
            }
            val id = claimId(input)
            if (storage.auditEvents.find(id) != null) {
                return@command if (claimedFor(input)) {
                    InputRecoveryClaim.AlreadyClaimed
                } else {
                    InputRecoveryClaim.Unavailable
                }
            }
            val claimed =
                if (input.delivery == SessionInputDelivery.STEER) {
                    if (!storage.sessionInputs.requeueAfterTargetFinished(input.inputId, input.revision, at)) {
                        return@command InputRecoveryClaim.Unavailable
                    } else {
                        // This is our own CAS result inside this transaction, never a newer external edit.
                        requireNotNull(storage.sessionInputs.get(input.inputId)).candidate()
                    }
                } else {
                    input
                }
            storage.auditEvents.append(id, input.sessionId, "input.recovery_started", "SYSTEM", "{}", at)
            InputRecoveryClaim.Claimed(claimed)
        }

    override fun resume(
        input: RecoveryInputCandidate,
        at: Long,
    ): Boolean =
        command {
            unchanged(input) && !stopped(input.sessionId) && claimedFor(input) &&
                storage.sessionInputs.resumePending(input.inputId, input.revision, at)
        }

    override fun fail(
        input: RecoveryInputCandidate,
        reason: String,
        notice: String,
        at: Long,
    ): Boolean =
        command {
            if (!unchanged(input) || stopped(input.sessionId) || !claimedFor(input)) return@command false
            if (!storage.sessionInputs.failPending(input.inputId, input.revision, reason, at)) {
                return@command false
            }
            storage.messages.append(
                "input-ended:${input.inputId}",
                input.sessionId,
                null,
                "SYSTEM",
                InputRecoveryPolicy.NOTICE_KIND,
                notice,
            )
            storage.auditEvents.append(
                "input-recovery-ended:${input.inputId}",
                input.sessionId,
                "input.recovery_ended",
                "SYSTEM",
                JsonObject(mapOf("reason" to JsonPrimitive(reason))).toString(),
                at,
            )
            true
        }

    private fun unchanged(input: RecoveryInputCandidate): Boolean =
        storage.sessionInputs.get(input.inputId)?.let {
            it.state == SessionInputState.NEEDS_ATTENTION && it.candidate() == input &&
                InputRecoveryPolicy.recoverable(it.blockedReason)
        } == true

    private fun SessionInputRecord.candidate() =
        RecoveryInputCandidate(
            inputId,
            sessionId,
            revision,
            delivery,
            expectedTurnId,
            blockedReason,
        )

    private fun stopped(sessionId: String): Boolean {
        val session = storage.sessions.find(sessionId) ?: return true
        return if (session.archivedAt != null) {
            true
        } else {
            val latest = storage.turns.listBySession(sessionId).lastOrNull()
            latest?.state == "CANCELLED" || latest?.pauseRequestedAt != null || latest?.errorCode == "USER_STOP"
        }
    }

    private fun claimedFor(input: RecoveryInputCandidate): Boolean =
        storage.auditEvents.find(claimId(input))?.let {
            it.correlationId == input.sessionId && it.type == "input.recovery_started" && it.actor == "SYSTEM"
        } == true

    private fun claimId(input: RecoveryInputCandidate) = "input-recovery:${input.inputId}"

    private fun <T> command(block: () -> T): T =
        synchronized(executionGate) {
            var result: T? = null
            storage.withTransaction { result = block() }
            @Suppress("UNCHECKED_CAST")
            result as T
        }
}
