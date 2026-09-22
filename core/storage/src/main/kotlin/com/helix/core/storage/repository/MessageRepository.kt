package com.helix.core.storage.repository

import com.helix.core.storage.content.ContentRef
import com.helix.core.storage.content.ContentStore
import com.helix.core.storage.dao.MessageDao
import com.helix.core.storage.entity.MessageEntity

@Suppress("TooManyFunctions") // Effective-history and retained-evidence operations share one repository.
class MessageRepository(
    private val dao: MessageDao,
    private val contentStore: ContentStore,
) {
    /**
     * Appends a message; [content] (when non-blank) is stored in [contentStore] and only the
     * reference is kept in Room (doc 9.2). Sequence allocation must run inside
     * [com.helix.core.storage.HelixStorage.withTransaction] when concurrent.
     */
    fun append(
        id: String,
        sessionId: String,
        turnId: String?,
        role: String,
        kind: String,
        content: String,
    ): MessageEntity {
        require(role.isNotBlank()) { "role must not be blank" }
        require(kind.isNotBlank()) { "kind must not be blank" }
        // maxSequence is COALESCE(MAX(sequence), -1), so max + 1 is always a valid sequence.
        val sequence = dao.maxSequence(sessionId) + 1
        val contentRef =
            if (content.isBlank()) {
                null
            } else {
                contentStore.write(content).toStorageString()
            }
        val entity =
            MessageEntity(
                id,
                sessionId,
                turnId,
                role,
                kind,
                contentRef,
                sequence,
                supersededBy = turnId?.let { dao.supersededRequest(it) },
            )
        dao.insert(entity)
        return entity
    }

    fun resolve(id: String): MessageEntity {
        val entity = dao.byId(id)

        return entity ?: throw IllegalArgumentException("message not found: $id")
    }

    /** Copy immutable history references only, never the source execution identity. Caller owns the transaction. */
    fun copyHistory(
        source: MessageEntity,
        id: String,
        sessionId: String,
    ): MessageEntity {
        val copied =
            source.copy(
                id = id,
                sessionId = sessionId,
                turnId = null,
                sequence =
                    dao.maxSequence(sessionId) + 1,
            )
        dao.insert(copied)
        return copied
    }

    fun supersededTurns(sessionId: String): Set<String> = dao.supersededTurns(sessionId).toSet()

    fun latestUser(sessionId: String): MessageEntity? = dao.latestUser(sessionId)

    fun allRevisions(sessionId: String): List<MessageEntity> = dao.allRevisions(sessionId)

    /** Caller owns the transaction with replacement Turn creation; history is retained for audit. */
    fun reviseLatest(
        sessionId: String,
        messageId: String,
        requestId: String,
    ) {
        val target = requireNotNull(dao.latestUser(sessionId)) { "REVISION_TARGET_CHANGED" }
        require(target.id == messageId) { "REVISION_TARGET_CHANGED" }
        dao.supersedeFrom(sessionId, target.sequence, requestId)
    }

    fun listBySession(sessionId: String): List<MessageEntity> = dao.listBySession(sessionId)

    fun pageAfter(
        sessionId: String,
        after: Long,
        limit: Int,
    ): List<MessageEntity> {
        require(limit in 1..256)
        return dao.pageAfter(sessionId, after, limit)
    }

    fun latestOfKind(
        sessionId: String,
        kind: String,
        includeSuperseded: Boolean = false,
    ): MessageEntity? = dao.latestOfKind(sessionId, kind, includeSuperseded)

    fun readContentBounded(
        message: MessageEntity,
        maxBytes: Int,
    ): String? = message.contentRef?.let { contentStore.readBounded(ContentRef.parse(it), maxBytes) }

    fun readContent(message: MessageEntity): String? {
        val ref = message.contentRef ?: return null
        return contentStore.read(ContentRef.parse(ref))
    }
}
