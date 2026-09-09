package com.helix.core.storage.repository

import com.helix.core.storage.content.ContentRef
import com.helix.core.storage.content.ContentStore
import com.helix.core.storage.dao.ToolResultDao
import com.helix.core.storage.entity.ToolResultEntity

class ToolResultRepository(
    private val dao: ToolResultDao,
    private val contentStore: ContentStore,
) {
    fun append(
        id: String,
        toolCallId: String,
        status: String,
        summary: String,
        content: String?,
    ): ToolResultEntity {
        require(status.isNotBlank()) { "status must not be blank" }
        require(summary.isNotBlank()) { "summary must not be blank" }
        val contentRef =
            if (content == null || content.isBlank()) {
                null
            } else {
                contentStore.write(content).toStorageString()
            }
        val entity = ToolResultEntity(id, toolCallId, status, summary, contentRef, false)
        dao.insert(entity)
        return entity
    }

    fun resolve(id: String): ToolResultEntity {
        val entity = dao.byId(id)

        return entity ?: throw IllegalArgumentException("tool result not found: $id")
    }

    fun byToolCall(toolCallId: String): ToolResultEntity? = dao.byToolCall(toolCallId)

    fun markVerified(result: ToolResultEntity) {
        require(!result.verified) { "tool result already verified: ${result.id}" }
        require(dao.markVerified(result.id) == 1) { "tool result could not be verified: ${result.id}" }
    }

    fun readContent(result: ToolResultEntity): String? {
        val ref = result.contentRef ?: return null
        return contentStore.read(ContentRef.parse(ref))
    }
}
