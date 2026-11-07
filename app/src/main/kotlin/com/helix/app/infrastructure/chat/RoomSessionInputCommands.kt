package com.helix.app.infrastructure.chat

import com.helix.core.model.input.SessionInputSpec
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.repository.SessionInputAcceptResult
import com.helix.core.storage.repository.SessionInputRecord
import com.helix.core.storage.repository.SessionInputState
import com.helix.feature.conversation.application.input.AcceptedInputReceipt
import com.helix.feature.conversation.application.input.InputAcceptance
import com.helix.feature.conversation.application.input.InputDeliveryKey
import com.helix.feature.conversation.application.input.SessionInputCommands

/** Owns the accepted-input write sets; a caller cannot split edit/park or accept/title transactions. */
@Suppress("TooManyFunctions") // Closed input lifecycle with atomic commands, not generic Room access.
internal class RoomSessionInputCommands(
    private val storage: HelixStorage,
) : SessionInputCommands {
    override fun acceptAndName(
        spec: SessionInputSpec,
        untitledName: String,
    ): InputAcceptance =
        transaction {
            if (!activeSession(spec.sessionId)) return@transaction InputAcceptance.Rejected("INPUT_SESSION_UNAVAILABLE")
            when (val result = storage.sessionInputs.accept(spec)) {
                is SessionInputAcceptResult.Rejected -> {
                    InputAcceptance.Rejected(result.reason)
                }

                is SessionInputAcceptResult.Accepted -> {
                    storage.sessions.nameUntitled(spec.sessionId, untitledName)
                    InputAcceptance.Accepted(result.record.receipt(), result.duplicate)
                }
            }
        }

    override fun receipt(inputId: String) = storage.sessionInputs.get(inputId)?.receipt()

    override fun pendingViews(sessionId: String) =
        storage.sessionInputs.listPending(sessionId).mapNotNull(SessionInputRecord::toPendingInputView)

    override fun readText(inputId: String) = storage.sessionInputs.get(inputId)?.let(storage.sessionInputs::readText)

    override fun sessionsWithPending() = storage.sessionInputs.sessionsWithPending()

    override fun hasPending(sessionId: String) = storage.sessionInputs.listPending(sessionId, limit = 1).isNotEmpty()

    override fun observeDeliveryRevision(sessionId: String) = storage.sessionInputs.observeDeliveryRevision(sessionId)

    override fun knowledgeReferencesForMessage(messageId: String) =
        storage.sessionInputs
            .forMessage(messageId)
            ?.knowledgeReferences
            .orEmpty()

    override fun queueHeadNeedsAttention(sessionId: String) =
        storage.sessionInputs.headQueue(sessionId)?.state == SessionInputState.NEEDS_ATTENTION

    override fun steer(
        key: InputDeliveryKey,
        expectedTurnId: String,
        at: Long,
    ): Boolean =
        transaction {
            if (editable(key) == null || !activeSession(key.sessionId)) return@transaction false
            storage.sessionInputs.steerPending(key.inputId, key.revision, expectedTurnId, at)
        }

    override fun withdraw(
        key: InputDeliveryKey,
        at: Long,
    ): Boolean =
        transaction {
            if (editable(key) == null) return@transaction false
            storage.sessionInputs.withdrawPending(key.inputId, key.revision, at)
        }

    override fun editTextAndPark(
        key: InputDeliveryKey,
        text: String,
        at: Long,
    ): Boolean =
        transaction {
            val original = editable(key) ?: return@transaction false
            val hasReference = original.reference != null || original.knowledgeReferences.isNotEmpty()
            val hasContent = text.isNotBlank() || original.attachments.isNotEmpty() || hasReference
            if (!hasContent) return@transaction false
            val nextRevision = Math.addExact(key.revision, 1L)
            val replacement =
                SessionInputSpec(
                    original.inputId,
                    original.sessionId,
                    original.delivery,
                    original.expectedTurnId,
                    nextRevision,
                    text,
                    original.attachments,
                    original.configuration,
                    at,
                    reference = storage.sessionInputs.readReference(original),
                    skillActivations = original.skillActivations,
                    knowledgeReferences = original.knowledgeReferences,
                )
            if (!storage.sessionInputs.editPending(key.inputId, key.revision, replacement)) return@transaction false
            // Failure must roll back the edited bytes too, never leave changed content eligible for delivery.
            check(storage.sessionInputs.markNeedsAttention(key.inputId, nextRevision, "INPUT_EDITED", at))
            true
        }

    override fun resume(
        key: InputDeliveryKey,
        at: Long,
    ): Boolean =
        transaction {
            if (editable(key) == null || !activeSession(key.sessionId)) return@transaction false
            storage.sessionInputs.resumePending(key.inputId, key.revision, at)
        }

    override fun park(
        key: InputDeliveryKey,
        reason: String,
        at: Long,
    ): Boolean =
        transaction {
            if (editable(key) == null) return@transaction false
            storage.sessionInputs.markNeedsAttention(key.inputId, key.revision, reason, at)
        }

    private fun editable(key: InputDeliveryKey): SessionInputRecord? =
        storage.sessionInputs.get(key.inputId)?.takeIf {
            it.sessionId == key.sessionId && it.revision == key.revision &&
                it.state in setOf(SessionInputState.PENDING, SessionInputState.NEEDS_ATTENTION)
        }

    private fun activeSession(sessionId: String) =
        storage.sessions.find(sessionId)?.let { it.archivedAt == null } == true

    private fun SessionInputRecord.receipt() =
        AcceptedInputReceipt(
            deliveryKey(),
            delivery,
            configuration.providerId,
            configuration.fingerprint,
            consumedTurnId,
            state == SessionInputState.PENDING,
        )

    private fun <T> transaction(block: () -> T): T {
        var result: T? = null
        storage.withTransaction { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}
