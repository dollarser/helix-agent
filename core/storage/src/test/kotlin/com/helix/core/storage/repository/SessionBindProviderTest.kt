package com.helix.core.storage.repository

import com.helix.core.storage.assertThrows
import com.helix.core.storage.dao.SessionDao
import com.helix.core.storage.entity.SessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * HXA-056: [SessionRepository.bindProvider] — a share-draft session is created provider-free
 * and binds a provider through the ONE closed path (the `providerId IS NULL` guard): a
 * session that ever carried a provider is never re-bound, and a missing session fails
 * closed. The egress target of an already-bound session is never swapped by this path.
 */
class SessionBindProviderTest {
    @Test
    fun bindAssignsProviderAndModelToAPrimaryKeyMatch() {
        val dao = FakeSessionDao()
        dao.rows["s-1"] = session("s-1")
        SessionRepository(dao).bindProvider("s-1", "prov-1", "model-1")
        val bound = dao.rows.getValue("s-1")
        assertEquals("prov-1", bound.providerId)
        assertEquals("model-1", bound.modelId)
    }

    @Test
    fun bindFailsClosedWhenAlreadyBound() {
        val dao = FakeSessionDao()
        dao.rows["s-1"] = session("s-1", providerId = "prov-old", modelId = "model-old")
        assertThrows("an already-bound session is never re-bound") {
            SessionRepository(dao).bindProvider("s-1", "prov-new", "model-new")
        }
        val untouched = dao.rows.getValue("s-1")
        assertEquals("the original egress target survives", "prov-old", untouched.providerId)
        assertEquals("model-old", untouched.modelId)
    }

    @Test
    fun bindFailsClosedWhenMissing() {
        val dao = FakeSessionDao()
        assertThrows("a missing session is not bindable") {
            SessionRepository(dao).bindProvider("nope", "prov-1", "model-1")
        }
    }

    @Test
    fun bindRejectsBlankInputsBeforeTouchingTheDao() {
        val dao = FakeSessionDao()
        dao.rows["s-1"] = session("s-1")
        assertThrows("blank providerId") { SessionRepository(dao).bindProvider("s-1", "  ", "model-1") }
        assertThrows("blank modelId") { SessionRepository(dao).bindProvider("s-1", "prov-1", "") }
        assertNull("the row must be untouched by rejected binds", dao.rows.getValue("s-1").providerId)
    }

    // --- fakes ---------------------------------------------------------------

    private fun session(
        id: String,
        providerId: String? = null,
        modelId: String? = null,
    ): SessionEntity = SessionEntity(id, "t", providerId, modelId, 0L, null)

    /**
     * Mirrors the DAO's `UPDATE sessions SET providerId, modelId WHERE id = :id AND
     * providerId IS NULL` row-count semantics (0 on missing or already-bound).
     */
    private class FakeSessionDao : SessionDao {
        val rows = HashMap<String, SessionEntity>()

        override fun insert(session: SessionEntity) {
            rows[session.id] = session
        }

        override fun byId(id: String): SessionEntity? = rows[id]

        override fun list(): List<SessionEntity> = rows.values.sortedByDescending { it.createdAt }

        override fun archive(
            id: String,
            archivedAt: Long,
        ): Int =
            rows.keys.singleOrNull { it == id }?.let {
                rows[it] = rows[it]!!.copy(archivedAt = archivedAt)
                1
            } ?: 0

        override fun bindProvider(
            id: String,
            providerId: String,
            modelId: String,
        ): Int {
            val existing = rows[id]
            if (existing != null && existing.providerId == null) {
                rows[id] = existing.copy(providerId = providerId, modelId = modelId)
                return 1
            }
            return 0
        }
    }
}
