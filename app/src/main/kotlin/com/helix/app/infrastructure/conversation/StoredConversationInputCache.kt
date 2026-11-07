package com.helix.app.infrastructure.conversation

import com.helix.core.storage.input.ComposerInputCache
import com.helix.feature.conversation.application.ConversationInputCache
import com.helix.feature.conversation.input.ChatSubmission

internal class StoredConversationInputCache(
    private val cache: ComposerInputCache,
) : ConversationInputCache {
    override fun read(sessionId: String): ChatSubmission? = cache.get(sessionId)?.toSubmission()

    override fun save(input: ChatSubmission): Boolean = cache.save(input.toInputSnapshot())

    override fun clear(
        sessionId: String,
        revision: Long,
        requestId: String,
    ): Boolean = cache.clear(sessionId, revision, requestId)

    override fun replace(
        expected: ChatSubmission,
        replacement: ChatSubmission,
    ): ChatSubmission? =
        synchronized(cache) {
            require(replacement.sessionId == expected.sessionId && replacement.revision > expected.revision)
            val current = read(expected.sessionId)
            if (current != expected) return@synchronized current
            save(replacement)
            read(expected.sessionId)
        }
}
