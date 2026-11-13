package com.helix.core.storage.repository

import com.helix.core.model.input.ConversationReferenceKind
import com.helix.core.model.input.InputAttachment
import com.helix.core.model.input.InputConfiguration
import com.helix.core.model.input.InputSkillActivation
import com.helix.core.model.input.KnowledgeReference
import com.helix.core.model.input.SessionInputDelivery

enum class SessionInputState { PENDING, NEEDS_ATTENTION, APPENDED, WITHDRAWN, FAILED }

/** Immutable accepted reference metadata; body stays in ContentStore until delivery. */
data class InputConversationReference(
    val sourceSessionId: String,
    val sourceSessionTitle: String,
    val selectionKind: ConversationReferenceKind,
    val sourceMessageIds: List<String>,
    val contentRef: String,
    val contentBytes: Long,
    val contentSha256: String,
)

/** Content remains in ContentStore; listing a queue never loads its bodies. */
data class SessionInputRecord(
    val schemaVersion: Int,
    val inputId: String,
    val sessionId: String,
    val sequence: Long,
    val delivery: SessionInputDelivery,
    val expectedTurnId: String?,
    val revision: Long,
    val textRef: String,
    val textBytes: Long,
    val attachments: List<InputAttachment>,
    val configuration: InputConfiguration,
    val state: SessionInputState,
    val consumedTurnId: String?,
    val messageId: String?,
    val requestModelCallId: String?,
    val blockedReason: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val reference: InputConversationReference? = null,
    val skillActivations: List<InputSkillActivation> = emptyList(),
    val skillActivationsRevoked: Boolean = false,
    val knowledgeReferences: List<KnowledgeReference> = emptyList(),
    val delegation: com.helix.core.model.input.ModelDelegationOrigin? = null,
) {
    val origin: com.helix.core.model.input.SessionInputOrigin
        get() =
            if (delegation == null) {
                com.helix.core.model.input.SessionInputOrigin.USER
            } else {
                com.helix.core.model.input.SessionInputOrigin.MODEL_DELEGATION
            }
}

sealed interface SessionInputAcceptResult {
    data class Accepted(
        val record: SessionInputRecord,
        val duplicate: Boolean,
    ) : SessionInputAcceptResult

    data class Rejected(
        val reason: String,
    ) : SessionInputAcceptResult
}
