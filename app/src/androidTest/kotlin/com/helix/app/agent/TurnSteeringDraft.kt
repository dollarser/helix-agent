package com.helix.app.agent

import com.helix.core.model.input.ConversationReferenceSnapshotInput
import com.helix.core.storage.repository.MessageAttachmentRepository

/** Materialized and checked outside the transaction; identity is rechecked before consuming. */
internal data class TurnSteeringDraft(
    val input: com.helix.core.storage.repository.SessionInputRecord,
    val content: String,
    val attachments: List<MessageAttachmentRepository.Binding>,
    val references: List<ConversationReferenceSnapshotInput> = emptyList(),
)

/** Convert checked host bytes to a platform-neutral command; the Room adapter rechecks exact identity. */
internal fun TurnSteeringDraft.toRoundInput(): com.helix.core.agent.turn.TurnSteeringInput =
    com.helix.core.agent.turn.TurnSteeringInput(
        input.inputId,
        input.revision,
        input.configuration.let {
            com.helix.core.agent.turn
                .TurnSteeringConfiguration(it.providerId, it.modelId, it.mode, it.fingerprint)
        },
        content,
        attachments.map {
            com.helix.core.agent.turn
                .TurnInputAttachment(it.artifactId, it.purpose, it.boundSha256)
        },
        references.map {
            com.helix.core.agent.ConversationReferenceIntent(
                it.sourceSessionId,
                it.sourceSessionTitle,
                it.selectionKind.name,
                it.sourceMessageIds,
                it.content,
                it.contentSha256,
            )
        },
    )
