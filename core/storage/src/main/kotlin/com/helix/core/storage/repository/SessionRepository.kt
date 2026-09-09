package com.helix.core.storage.repository

import com.helix.core.storage.dao.SessionDao
import com.helix.core.storage.entity.SessionEntity

class SessionRepository(
    private val dao: SessionDao,
) {
    fun create(
        id: String,
        title: String,
        providerId: String?,
        modelId: String?,
        createdAt: Long,
    ): SessionEntity {
        require(title.isNotBlank()) { "session title must not be blank" }
        require(createdAt >= 0) { "createdAt must be >= 0" }
        val entity = SessionEntity(id, title, providerId, modelId, createdAt, null)
        dao.insert(entity)
        return entity
    }

    fun resolve(id: String): SessionEntity {
        val entity = dao.byId(id)

        return entity ?: throw IllegalArgumentException("session not found: $id")
    }

    fun list(): List<SessionEntity> = dao.list()

    fun updateDetails(
        id: String,
        title: String,
        directoryRef: String?,
    ) {
        require(title.isNotBlank() && title.length <= 200 && '\u0000' !in title)
        require(directoryRef == null || directoryRef.length <= 4096)
        require(dao.updateDetails(id, title.trim(), directoryRef) == 1)
    }

    fun archive(
        id: String,
        archivedAt: Long,
    ) {
        require(archivedAt >= 0) { "archivedAt must be >= 0" }
        val updated = dao.archive(id, archivedAt)
        require(updated == 1) { "session not archivable: $id" }
    }

    fun restore(id: String) {
        require(dao.restore(id) == 1 || resolve(id).archivedAt == null)
    }

    /**
     * Binds a provider+model to a session created WITHOUT one (HXA-056 draft sessions).
     * Fails closed (require) when the session is missing or already bound — an already-bound
     * session's egress target is never swapped through this path.
     */
    fun bindProvider(
        id: String,
        providerId: String,
        modelId: String,
    ) {
        require(providerId.isNotBlank() && modelId.isNotBlank()) { "provider and model must not be blank" }
        val updated = dao.bindProvider(id, providerId, modelId)
        require(updated == 1) { "session not bindable (missing or already bound): $id" }
    }

    /** Explicit user selection for future turns, without altering history or initial-bind semantics. */
    fun selectModel(
        id: String,
        providerId: String,
        modelId: String,
    ) {
        require(providerId.isNotBlank() && modelId.isNotBlank())
        require(dao.selectModel(id, providerId, modelId) == 1) { "session model selection unavailable" }
    }

    // No delete: sessions are archived, never deleted (doc 9.1 / entity contract). A hard
    // delete would cascade the session's approvals/executions audit rows, which must be
    // durable (AGENTS.md: every tool call goes through audit). A retention wipe, if ever
    // authorized, is a future HXA decision with its own review.
}
