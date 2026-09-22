package com.helix.app.chat

/** Immutable identity supplied by the composer; an edit must create a new revision and request ID. */
data class ChatSubmission(
    val sessionId: String,
    val revision: Long,
    val clientRequestId: String,
    val text: String,
    val attachmentIds: List<String> = emptyList(),
) {
    init {
        require(sessionId.isNotBlank() && clientRequestId.isNotBlank())
        require(revision >= 0)
    }
}

/** Only Accepted permits clearing the matching composer revision. No result grants tool approval. */
data class ChatSubmissionReceipt(
    val submission: ChatSubmission,
    val outcome: ChatSubmissionOutcome,
)

sealed interface ChatSubmissionOutcome {
    data class Accepted(
        val turnId: String,
    ) : ChatSubmissionOutcome

    data object PendingConfirmation : ChatSubmissionOutcome

    data class Rejected(
        val reason: String,
    ) : ChatSubmissionOutcome
}

internal fun ChatSubmission.toDraftEntity() =
    com.helix.core.storage.entity.ComposerDraftEntity(
        sessionId,
        revision,
        clientRequestId,
        text,
        kotlinx.serialization.json
            .JsonArray(attachmentIds.map { kotlinx.serialization.json.JsonPrimitive(it) })
            .toString(),
    )

internal fun com.helix.core.storage.entity.ComposerDraftEntity.toSubmission(): ChatSubmission {
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
    )
}
