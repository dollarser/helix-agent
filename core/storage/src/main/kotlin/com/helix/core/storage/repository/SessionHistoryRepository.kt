package com.helix.core.storage.repository

import com.helix.core.storage.content.ContentRef
import com.helix.core.storage.content.ContentStore
import com.helix.core.storage.dao.SessionHistoryDao
import com.helix.core.storage.entity.MessageEntity
import com.helix.core.storage.entity.MessageReferenceSnapshotEntity

/** Identity supplied by the trusted Dispatcher, not JSON arguments or the currently visible page. */
data class SessionHistoryCaller(
    val sessionId: String,
    val turnId: String,
    val callId: String,
    val toolName: String,
)

data class SessionHistoryPage(
    val messages: List<MessageEntity>,
    val nextBeforeSequence: Long?,
)

data class SessionHistoryContent(
    val content: String,
    val sha256: String,
)

/** Existing Room facts and ContentStore only; no new transcript, permission, or execution owner. */
class SessionHistoryRepository(
    private val dao: SessionHistoryDao,
    private val contentStore: ContentStore,
) {
    fun caller(caller: SessionHistoryCaller) =
        requireNotNull(dao.caller(caller.sessionId, caller.turnId, caller.callId, caller.toolName)) {
            "SESSION_CALL_UNAVAILABLE"
        }

    fun page(
        caller: SessionHistoryCaller,
        before: Long,
        limit: Int,
    ): SessionHistoryPage {
        require(before >= 0 && limit in 1..MAX_PAGE)
        caller(caller)
        val rows = dao.page(caller.sessionId, before, limit + 1)
        val page = rows.take(limit)
        return SessionHistoryPage(page, page.lastOrNull()?.sequence?.takeIf { rows.size > limit })
    }

    fun message(
        caller: SessionHistoryCaller,
        messageId: String,
    ): MessageEntity {
        caller(caller)
        return requireNotNull(dao.message(caller.sessionId, messageId)) { "SESSION_MESSAGE_UNAVAILABLE" }
    }

    fun references(
        caller: SessionHistoryCaller,
        messageId: String,
    ): List<MessageReferenceSnapshotEntity> {
        message(caller, messageId)
        return dao.references(messageId)
    }

    fun read(
        caller: SessionHistoryCaller,
        messageId: String,
        referenceOrdinal: Int?,
    ): SessionHistoryContent {
        val message = message(caller, messageId)
        val ref =
            if (referenceOrdinal == null) {
                ContentRef.parse(requireNotNull(message.contentRef))
            } else {
                val snapshot = requireNotNull(references(caller, messageId).find { it.ordinal == referenceOrdinal })
                ContentRef.parse(snapshot.contentRef).also {
                    require(it.sha256 == snapshot.contentSha256) { "SESSION_REFERENCE_INTEGRITY" }
                }
            }
        val content = contentStore.readBounded(ref, MAX_CONTENT_BYTES)
        // A concurrent revision/deletion or terminated caller must not publish stale content.
        require(message(caller, messageId).contentRef == message.contentRef) { "SESSION_MESSAGE_CHANGED" }
        return SessionHistoryContent(content, ref.sha256)
    }

    fun rename(
        caller: SessionHistoryCaller,
        expectedTitle: String,
        title: String,
    ): Boolean {
        require(caller.toolName == "session.rename")
        require(expectedTitle.length <= 200 && title.isNotBlank() && title.length <= 200 && '\u0000' !in title)
        return dao.rename(
            caller.sessionId,
            caller.turnId,
            caller.callId,
            expectedTitle,
            title.trim(),
            com.helix.core.storage.SearchText
                .fold(title.trim()),
        ) == 1
    }

    companion object {
        const val MAX_PAGE = 40
        const val MAX_CONTENT_BYTES = 262_144
    }
}
