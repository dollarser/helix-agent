package com.helix.core.storage.repository

import com.helix.core.storage.SessionSummary
import com.helix.core.storage.dao.SessionDao
import com.helix.core.storage.entity.SessionEntity
import kotlinx.coroutines.flow.Flow

@Suppress("TooManyFunctions") // One session aggregate, including bounded privacy inventory; no second state owner.
class SessionRepository(
    private val dao: SessionDao,
    private val transaction: (() -> Unit) -> Unit = { it() },
    private val directoryForSession: (String, String?) -> String? = { _, ref -> ref },
    private val bindWorkspace: (String, String) -> Unit = { _, _ -> },
    private val snapshotPermissions: (String, Long) -> Unit = { _, _ -> },
) {
    fun create(
        id: String,
        title: String,
        providerId: String?,
        modelId: String?,
        createdAt: Long,
        directoryRef: String? = null,
    ): SessionEntity {
        require(title == title.trim() && title.length <= 200 && '\u0000' !in title)
        require(createdAt >= 0) { "createdAt must be >= 0" }
        require(directoryRef == null || directoryRef.length <= 4096)
        val directory = directoryForSession(id, directoryRef)
        val entity = SessionEntity(id, title, providerId, modelId, createdAt, null, directory)
        transaction {
            dao.insert(entity)
            directory?.let { bindWorkspace(id, it) }
            snapshotPermissions(id, createdAt)
        }
        return entity
    }

    fun resolve(id: String): SessionEntity {
        val entity = dao.byId(id)

        return entity ?: throw IllegalArgumentException("session not found: $id")
    }

    fun pageIds(
        afterId: String?,
        limit: Int,
    ): List<String> {
        require(limit in 1..256)
        return dao.pageIds(afterId, limit)
    }

    fun list(): List<SessionEntity> = dao.list()

    fun observeSummaries(): Flow<List<SessionSummary>> = dao.observeSummaries()

    fun find(id: String): SessionEntity? = dao.byId(id)

    /** Called in the input acceptance transaction, not when optional composer state is cached. */
    fun nameUntitled(
        id: String,
        title: String,
    ): Boolean {
        require(title.isNotBlank() && title.length <= 200 && '\u0000' !in title)
        val clean = title.trim()
        return dao.nameUntitled(
            id,
            clean,
            com.helix.core.storage.SearchText
                .fold(clean),
        ) == 1
    }

    fun updateDetails(
        id: String,
        title: String,
        directoryRef: String?,
    ) {
        require(title.length <= 200 && '\u0000' !in title)
        require(directoryRef == null || directoryRef.length <= 4096)
        val directory = directoryForSession(id, directoryRef)
        transaction {
            require(
                dao.updateDetails(
                    id,
                    title.trim(),
                    directory,
                    com.helix.core.storage.SearchText
                        .fold(title.trim()),
                ) ==
                    1,
            )
            directory?.let { bindWorkspace(id, it) }
        }
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

    /** Caller owns the transaction with the future RunControl update; the active snapshot is immutable. */
    fun selectFutureModel(
        id: String,
        providerId: String,
        modelId: String,
        turnId: String,
        toolCallId: String,
    ): Boolean {
        require(providerId.isNotBlank() && modelId.isNotBlank())
        return dao.selectFutureModel(id, providerId, modelId, turnId, toolCallId) == 1
    }

    // Explicit privacy erasure is coordinated by HelixStorage with shared-content reference checks.
}
