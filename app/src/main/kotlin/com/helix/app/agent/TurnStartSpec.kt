package com.helix.app.agent

import com.helix.core.model.input.ConversationReferenceSnapshotInput
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
