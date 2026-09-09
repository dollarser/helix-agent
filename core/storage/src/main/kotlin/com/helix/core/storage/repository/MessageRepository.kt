package com.helix.core.storage.repository

import com.helix.core.storage.content.ContentRef
import com.helix.core.storage.content.ContentStore
import com.helix.core.storage.dao.MessageDao
import com.helix.core.storage.entity.MessageEntity

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
        val entity = MessageEntity(id, sessionId, turnId, role, kind, contentRef, sequence)
        dao.insert(entity)
        return entity
    }

    fun resolve(id: String): MessageEntity {
        val entity = dao.byId(id)

        return entity ?: throw IllegalArgumentException("message not found: $id")
    }

    fun listBySession(sessionId: String): List<MessageEntity> = dao.listBySession(sessionId)

    fun readContent(message: MessageEntity): String? {
        val ref = message.contentRef ?: return null
        return contentStore.read(ContentRef.parse(ref))
    }
}
