package com.helix.app.agent

import com.helix.core.storage.repository.ConversationReferenceSnapshotInput
import com.helix.core.storage.repository.MessageAttachmentRepository

internal data class TurnStartSpec(
    val sessionId: String,
    val turnId: String,
    val firstModelCallId: String,
    val providerSnapshot: String,
    val userText: String?,
    val attachments: List<MessageAttachmentRepository.Binding> = emptyList(),
    val references: List<ConversationReferenceSnapshotInput> = emptyList(),
    val goalRunId: String? = null,
    // The persistent submit-dedup receipt (research doc section 34; HX2-01 §2e): written onto the
    // turn row atomically with the turn so the client-request id survives a restart.
    val clientRequestId: String? = null,
    val inputFingerprint: String? = null,
    val revisedMessageId: String? = null,
    val inputRevision: Long? = null,
    val regenerateMessageId: String? = null,
    val recoveryFromTurnId: String? = null,
)

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
