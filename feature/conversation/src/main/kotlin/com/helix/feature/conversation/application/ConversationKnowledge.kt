package com.helix.feature.conversation.application

import com.helix.core.model.input.KnowledgeReference

/** Explicit user library operations; neither validation nor saving a reply grants tool authority. */
interface ConversationKnowledge {
    suspend fun valid(items: List<KnowledgeReference>): Boolean

    suspend fun saveReply(
        sessionId: String,
        messageId: String,
    ): Boolean
}
