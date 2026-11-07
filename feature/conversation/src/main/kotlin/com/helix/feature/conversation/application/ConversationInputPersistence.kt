package com.helix.feature.conversation.application

import com.helix.feature.conversation.input.ChatSubmission
import com.helix.feature.conversation.input.ChatSubmissionOutcome

/** Optional input cache; these operations never admit a Turn or own durable acceptance. */
interface ConversationInputCache {
    fun read(sessionId: String): ChatSubmission?

    fun save(input: ChatSubmission): Boolean

    fun clear(
        sessionId: String,
        revision: Long,
        requestId: String,
    ): Boolean

    /** Exact snapshot CAS; a delayed recovery cannot overwrite a newer edit in this session. */
    fun replace(
        expected: ChatSubmission,
        replacement: ChatSubmission,
    ): ChatSubmission?
}

/** Durable receipt lookup for direct Goal, explicit retry and history revision submissions. */
interface ConversationSubmissionHistory {
    fun completed(request: ChatSubmission): ChatSubmissionOutcome?

    fun acceptedTurnId(
        requestId: String,
        sessionId: String,
    ): String?
}

data class MessageEditSnapshot(
    val sessionId: String,
    val messageId: String,
    val contentVersion: String?,
    val text: String,
)

enum class MessageEditResult { SAVED, BUSY, CHANGED, UNAVAILABLE, FAILED }

/** The implementation owns edit eligibility, content CAS, derived-history invalidation and audit together. */
interface ConversationMessageEditor {
    fun load(
        sessionId: String,
        messageId: String,
    ): MessageEditSnapshot

    fun save(
        snapshot: MessageEditSnapshot,
        text: String,
    ): MessageEditResult
}
