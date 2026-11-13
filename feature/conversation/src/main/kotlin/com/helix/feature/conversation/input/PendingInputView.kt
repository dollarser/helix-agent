package com.helix.feature.conversation.input

import com.helix.core.model.input.KnowledgeReference
import com.helix.core.model.input.SessionInputDelivery

/** Undelivered input presentation; actions retain this displayed identity and revision. */
data class PendingInputView(
    val inputId: String,
    val revision: Long,
    val delivery: SessionInputDelivery,
    val needsAttention: Boolean,
    val knowledgeReferences: List<KnowledgeReference> = emptyList(),
    val delegationSourceSessionId: String? = null,
) {
    val canEdit: Boolean get() = delegationSourceSessionId == null
    val canSend: Boolean get() = needsAttention || (canEdit && delivery == SessionInputDelivery.QUEUE)
}
