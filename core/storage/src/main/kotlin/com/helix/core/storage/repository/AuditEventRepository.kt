package com.helix.core.storage.repository

import com.helix.core.storage.dao.AuditEventDao
import com.helix.core.storage.entity.AuditEventEntity

class AuditEventRepository(
    private val dao: AuditEventDao,
) {
    fun append(
        id: String,
        correlationId: String,
        type: String,
        actor: String,
        redactedPayload: String,
        timestamp: Long,
    ): AuditEventEntity {
        require(correlationId.isNotBlank()) { "correlationId must not be blank" }
        require(type.isNotBlank()) { "event type must not be blank" }
        require(actor.isNotBlank()) { "actor must not be blank" }
        require(timestamp >= 0) { "timestamp must be >= 0" }
        val entity = AuditEventEntity(id, correlationId, type, actor, redactedPayload, timestamp)
        dao.append(entity)
        return entity
    }

    fun resolve(id: String): AuditEventEntity {
        val entity = dao.byId(id)

        return entity ?: throw IllegalArgumentException("audit event not found: $id")
    }

    fun listByCorrelation(correlationId: String): List<AuditEventEntity> = dao.listByCorrelation(correlationId)

    fun deleteByCorrelations(correlationIds: List<String>): Int =
        if (correlationIds.isEmpty()) 0 else dao.deleteByCorrelations(correlationIds.distinct())

    /**
     * The newest [limit] audit rows, newest first — the audit log page's bounded load
     * (roadmap HXA-036; the page filters these in memory and never loads the whole table).
     */
    fun recent(limit: Int): List<AuditEventEntity> {
        require(limit in 1..MAX_RECENT_LIMIT) { "recent limit must be in 1..$MAX_RECENT_LIMIT" }
        return dao.recent(limit)
    }

    private companion object {
        const val MAX_RECENT_LIMIT = 1000
    }
}
