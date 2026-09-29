package com.helix.app.chat

import java.util.concurrent.atomic.AtomicLong

/** Process-local edit ordering; observes loaded values before issuing a newer user edit. */
internal object ComposerEditClock {
    private val sequence = AtomicLong()

    fun next(after: Long): Long = sequence.updateAndGet { maxOf(it, after) + 1 }
}

/** Immutable clicked input. Edit order is independent of optional cache write success. */
data class ChatSubmission(
    val sessionId: String,
    val revision: Long,
    val clientRequestId: String,
    val text: String,
    val attachmentIds: List<String> = emptyList(),
    val revisedMessageId: String? = null,
    val delivery: com.helix.core.storage.repository.SessionInputDelivery =
        com.helix.core.storage.repository.SessionInputDelivery.QUEUE,
    val expectedTurnId: String? = null,
    val referenceSourceSessionId: String? = null,
    val referenceKind: com.helix.core.storage.repository.ConversationReferenceKind? = null,
) {
    init {
        require(sessionId.isNotBlank() && clientRequestId.isNotBlank())
        require(revision >= 0)
        require((referenceSourceSessionId == null) == (referenceKind == null))
        require(referenceSourceSessionId == null || referenceSourceSessionId != sessionId)
    }
}

/** Accepted or Enqueued permits clearing the matching composer revision. No result grants tool approval. */
data class ChatSubmissionReceipt(
    val submission: ChatSubmission,
    val outcome: ChatSubmissionOutcome,
)

sealed interface ChatSubmissionOutcome {
    data class Accepted(
        val turnId: String,
    ) : ChatSubmissionOutcome

    data class Enqueued(
        val inputId: String,
    ) : ChatSubmissionOutcome

    data object PendingConfirmation : ChatSubmissionOutcome

    data class Rejected(
        val reason: String,
    ) : ChatSubmissionOutcome
}

internal fun ChatSubmission.toInputSnapshot() =
    com.helix.core.storage.input.ComposerInputSnapshot(
        sessionId,
        revision,
        clientRequestId,
        text,
        kotlinx.serialization.json
            .JsonArray(attachmentIds.map { kotlinx.serialization.json.JsonPrimitive(it) })
            .toString(),
        revisedMessageId,
        delivery.name,
        expectedTurnId,
        referenceSourceSessionId,
        referenceKind?.name,
    )

internal fun com.helix.core.storage.input.ComposerInputSnapshot.toSubmission(): ChatSubmission {
    val ids =
        kotlinx.serialization.json.Json.parseToJsonElement(
            attachmentIdsJson,
        ) as kotlinx.serialization.json.JsonArray
    return ChatSubmission(
        sessionId,
        revision,
        clientRequestId,
        text,
        ids.map {
            (it as kotlinx.serialization.json.JsonPrimitive).content
        },
        revisedMessageId,
        com.helix.core.storage.repository.SessionInputDelivery
            .valueOf(delivery),
        expectedTurnId,
        referenceSourceSessionId,
        referenceKind?.let(com.helix.core.storage.repository.ConversationReferenceKind::valueOf),
    )
}
