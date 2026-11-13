package com.helix.core.storage.repository

import com.helix.core.model.input.ConversationReferenceKind
import com.helix.core.model.input.ConversationReferenceSnapshotInput
import com.helix.core.model.input.InputAttachment
import com.helix.core.model.input.InputConfiguration
import com.helix.core.model.input.InputSkillActivations
import com.helix.core.model.input.KnowledgeReferenceCodec
import com.helix.core.model.input.SessionInputDelivery
import com.helix.core.storage.content.ContentRef
import com.helix.core.storage.content.ContentStore
import com.helix.core.storage.entity.SessionInputEntity
import kotlinx.serialization.json.jsonPrimitive

/** Accepted row and bound-content projections; no database writes or execution. */
internal fun SessionInputEntity.toInputRecord(attachments: List<InputAttachment>) =
    SessionInputRecord(
        schemaVersion,
        inputId,
        sessionId,
        sequence,
        SessionInputDelivery.valueOf(delivery),
        expectedTurnId,
        revision,
        textRef,
        textBytes,
        attachments,
        InputConfiguration(providerId, modelId, mode, configurationFingerprint),
        SessionInputState.valueOf(state),
        consumedTurnId,
        messageId,
        requestModelCallId,
        blockedReason,
        createdAt,
        updatedAt,
        referenceRecord(),
        InputSkillActivations.decode(skillActivationsJson),
        skillActivationsRevoked,
        KnowledgeReferenceCodec.decode(knowledgeReferencesJson),
        SessionInputDelegation.origin(this),
    )

private fun SessionInputEntity.referenceRecord(): InputConversationReference? {
    val sourceId = referenceSourceSessionId ?: return null
    val title = requireNotNull(referenceSourceSessionTitle)
    val kind = ConversationReferenceKind.valueOf(requireNotNull(referenceSelectionKind))
    val messageIds =
        (
            kotlinx.serialization.json.Json
                .parseToJsonElement(requireNotNull(referenceSourceMessageIdsJson))
                as kotlinx.serialization.json.JsonArray
        ).map { it.jsonPrimitive.content }
    return InputConversationReference(
        sourceSessionId = sourceId,
        sourceSessionTitle = title,
        selectionKind = kind,
        sourceMessageIds = messageIds,
        contentRef = requireNotNull(referenceContentRef),
        contentBytes = referenceContentBytes,
        contentSha256 = requireNotNull(referenceContentSha256),
    )
}

internal fun SessionInputRecord.materializeReference(contentStore: ContentStore): ConversationReferenceSnapshotInput? =
    reference?.let { reference ->
        val ref = ContentRef.parse(reference.contentRef)
        require(ref.sha256 == reference.contentSha256 && ref.size == reference.contentBytes)
        ConversationReferenceSnapshotInput(
            sourceSessionId = reference.sourceSessionId,
            sourceSessionTitle = reference.sourceSessionTitle,
            selectionKind = reference.selectionKind,
            sourceMessageIds = reference.sourceMessageIds,
            content =
                contentStore.readBounded(
                    ref,
                    ConversationReferenceSnapshotInput.MAX_CONTENT_BYTES,
                ),
        )
    }
