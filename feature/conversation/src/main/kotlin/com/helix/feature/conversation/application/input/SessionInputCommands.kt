package com.helix.feature.conversation.application.input

import com.helix.core.model.input.KnowledgeReference
import com.helix.core.model.input.SessionInputDelivery
import com.helix.core.model.input.SessionInputSpec
import com.helix.feature.conversation.application.EgressDisclosure
import com.helix.feature.conversation.application.attachment.AttachmentSendAdmission
import com.helix.feature.conversation.input.PendingInputView
import kotlinx.coroutines.flow.Flow

/** Receipt facts needed for duplicate submission, acknowledgment and explicit delivery actions. */
data class AcceptedInputReceipt(
    val key: InputDeliveryKey,
    val delivery: SessionInputDelivery,
    val providerId: String,
    val configurationFingerprint: String,
    val consumedTurnId: String?,
    val isPending: Boolean,
) {
    val inputId: String get() = key.inputId
    val sessionId: String get() = key.sessionId
    val revision: Long get() = key.revision
}

sealed interface InputAcceptance {
    data class Accepted(
        val receipt: AcceptedInputReceipt,
        val duplicate: Boolean,
    ) : InputAcceptance

    data class Rejected(
        val reason: String,
    ) : InputAcceptance
}

/**
 * Closed persistence actions. Admission authorization remains the caller's independent responsibility.
 * Every mutation binds the session and exact displayed revision, and rechecks durable eligibility.
 */
@Suppress("TooManyFunctions") // One accepted-input lifecycle; no records or generic transaction callbacks escape.
interface SessionInputCommands {
    fun acceptAndName(
        spec: SessionInputSpec,
        untitledName: String,
    ): InputAcceptance

    fun receipt(inputId: String): AcceptedInputReceipt?

    fun pendingViews(sessionId: String): List<PendingInputView>

    fun readText(inputId: String): String?

    fun sessionsWithPending(): List<String>

    fun hasPending(sessionId: String): Boolean

    fun observeDeliveryRevision(sessionId: String): Flow<Long>

    fun knowledgeReferencesForMessage(messageId: String): List<KnowledgeReference>

    fun queueHeadNeedsAttention(sessionId: String): Boolean

    fun steer(
        key: InputDeliveryKey,
        expectedTurnId: String,
        at: Long,
    ): Boolean

    fun withdraw(
        key: InputDeliveryKey,
        at: Long,
    ): Boolean

    fun editTextAndPark(
        key: InputDeliveryKey,
        text: String,
        at: Long,
    ): Boolean

    fun resume(
        key: InputDeliveryKey,
        at: Long,
    ): Boolean

    fun park(
        key: InputDeliveryKey,
        reason: String,
        at: Long,
    ): Boolean
}

/** Disclosure reopens the exact accepted revision and bytes, never a mutable composer selection. */
fun interface AcceptedInputDisclosure {
    fun disclosure(
        key: InputDeliveryKey,
        target: EgressDisclosure.EgressTarget,
        strings: (Int, Array<out Any>) -> String,
    ): AttachmentSendAdmission.Outcome
}
