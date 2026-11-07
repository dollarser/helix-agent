package com.helix.feature.conversation.input

import java.util.concurrent.atomic.AtomicLong

/** Process-local edit ordering; observes loaded values before issuing a newer user edit. */
object ComposerEditClock {
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
    val delivery: com.helix.core.model.input.SessionInputDelivery =
        com.helix.core.model.input.SessionInputDelivery.QUEUE,
    val expectedTurnId: String? = null,
    val referenceSourceSessionId: String? = null,
    val referenceKind: com.helix.core.model.input.ConversationReferenceKind? = null,
    val skillActivations: List<com.helix.core.model.input.InputSkillActivation> = emptyList(),
    val knowledgeReferences: List<com.helix.core.model.input.KnowledgeReference> = emptyList(),
) {
    init {
        require(sessionId.isNotBlank() && clientRequestId.isNotBlank())
        require(revision >= 0)
        com.helix.core.model.input.InputSkillActivations
            .encode(skillActivations)
        com.helix.core.model.input.KnowledgeReferenceCodec
            .encode(knowledgeReferences)
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
