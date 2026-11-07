package com.helix.app.infrastructure.conversation

import com.helix.feature.conversation.input.ChatSubmission

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
        com.helix.core.model.input.InputSkillActivations
            .encode(skillActivations),
        com.helix.core.model.input.KnowledgeReferenceCodec
            .encode(knowledgeReferences),
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
        com.helix.core.model.input.SessionInputDelivery
            .valueOf(delivery),
        expectedTurnId,
        referenceSourceSessionId,
        referenceKind?.let(com.helix.core.model.input.ConversationReferenceKind::valueOf),
        com.helix.core.model.input.InputSkillActivations
            .decode(skillActivationsJson),
        com.helix.core.model.input.KnowledgeReferenceCodec
            .decode(knowledgeReferencesJson),
    )
}
