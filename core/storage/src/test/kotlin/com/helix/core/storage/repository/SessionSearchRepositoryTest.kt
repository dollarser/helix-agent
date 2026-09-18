package com.helix.core.storage.repository

import com.helix.core.storage.assertThrows
import com.helix.core.storage.content.ContentRef
import com.helix.core.storage.content.FileContentStore
import com.helix.core.storage.dao.MessageDao
import com.helix.core.storage.dao.SessionDao
import com.helix.core.storage.entity.MessageEntity
import com.helix.core.storage.entity.SessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * HXA-191 search slice: [SessionSearchRepository] — bounded read-only title + message-body
 * search over the existing rows and content store: case-insensitive matching, snippet
 * window, archived flag, explicit scope (scanned/skipped/truncated) and fail-soft
 * handling of unreadable bodies.
 */
class SessionSearchRepositoryTest {
    @Test
    fun `title match is case-insensitive and flags title only`() {
        withFixture { fixture ->
            fixture.session("s-1", "Sunrise Planning")
            val result = fixture.search("sUnrISE")
            val hit = result.hits.single()
            assertEquals("s-1", hit.sessionId)
            assertEquals(setOf(SessionSearchMatchKind.TITLE), hit.matchedKinds)
            assertNull("a title-only hit carries no snippet", hit.messageSnippet)
        }
    }

    @Test
    fun `message body match yields a bounded snippet around the match`() {
        withFixture { fixture ->
            val session = fixture.session("s-1", "untitled")
            fixture.body(session.id, "prefix padding " + "x".repeat(80) + " needle " + "y".repeat(80) + " suffix")
            val result = fixture.search("needle")
            val hit = result.hits.single()
            assertEquals(setOf(SessionSearchMatchKind.MESSAGE), hit.matchedKinds)
            val snippet = hit.messageSnippet
            assertTrue("snippet must contain the match", snippet != null && snippet.contains("needle"))
            assertTrue("overlong context must be elided", snippet!!.startsWith("…") && snippet.endsWith("…"))
        }
    }

    @Test
    fun `title and body match report both kinds`() {
        withFixture { fixture ->
            val session = fixture.session("s-1", "needle title")
            fixture.body(session.id, "needle body")
            val hit = fixture.search("needle").hits.single()
            assertEquals(setOf(SessionSearchMatchKind.TITLE, SessionSearchMatchKind.MESSAGE), hit.matchedKinds)
        }
    }

    @Test
    fun `archived session is reported as archived`() {
        withFixture { fixture ->
            fixture.session("s-1", "archived title", archivedAt = 9000L)
            val hit = fixture.search("archived").hits.single()
            assertTrue(hit.isArchived)
        }
    }

    @Test
    fun `no match yields empty hits but still counts the scan`() {
        withFixture { fixture ->
            val session = fixture.session("s-1", "one")
            fixture.body(session.id, "alpha")
            val result = fixture.search("zzz")
            assertTrue(result.hits.isEmpty())
            assertEquals(1, result.scannedMessages)
            assertEquals(0, result.skippedMessages)
            assertFalse(result.truncated)
        }
    }

    @Test
    fun `blank query returns empty without reading anything`() {
        withFixture { fixture ->
            val session = fixture.session("s-1", "one")
            fixture.body(session.id, "alpha")
            val result = fixture.search("   ")
            assertEquals("", result.query)
            assertTrue(result.hits.isEmpty())
            assertEquals(0, result.scannedMessages)
            assertFalse(result.truncated)
        }
    }

    @Test
    fun `candidate cap bounds the scan and sets truncated`() {
        withFixture { fixture ->
            val session = fixture.session("s-1", "untitled")
            fixture.body(session.id, "needle one")
            fixture.body(session.id, "needle two")
            fixture.body(session.id, "needle three")
            val result = fixture.search("needle", maxCandidates = 2)
            assertTrue("the cap was reached, so the scope must be stated", result.truncated)
            assertEquals(2, result.scannedMessages)
            assertEquals("hits deduplicate per session", 1, result.hits.size)
        }
    }

    @Test
    fun `scan prefers newer sessions and the newest message first`() {
        withFixture { fixture ->
            val older = fixture.session("s-old", "untitled", createdAt = 1000L)
            val newer = fixture.session("s-new", "untitled", createdAt = 2000L)
            fixture.body(older.id, "needle older body")
            fixture.body(newer.id, "needle new one")
            fixture.body(newer.id, "needle new two")
            val result = fixture.search("needle")
            val hit = result.hits.single { it.sessionId == newer.id }
            assertTrue(
                "the snippet must come from the newest message",
                hit.messageSnippet != null && hit.messageSnippet.contains("new two"),
            )
            assertEquals(2, result.hits.size)
        }
    }

    @Test
    fun `oversized body is skipped and the search continues`() {
        withFixture { fixture ->
            val session = fixture.session("s-1", "untitled")
            fixture.body(session.id, "needle " + "z".repeat(4096))
            fixture.body(session.id, "needle small")
            val result = fixture.search("needle", maxContentBytes = 1024)
            assertEquals(1, result.skippedMessages)
            assertEquals(1, result.scannedMessages)
            val hit = result.hits.single()
            assertTrue(hit.messageSnippet != null && hit.messageSnippet.contains("small"))
        }
    }

    @Test
    fun `unreadable body is skipped without failing the search`() {
        withFixture { fixture ->
            val session = fixture.session("s-1", "untitled")
            fixture.orphanBody(session.id)
            fixture.body(session.id, "needle readable")
            val result = fixture.search("needle")
            assertEquals(1, result.skippedMessages)
            assertEquals(1, result.scannedMessages)
            assertTrue(result.hits.single().messageSnippet != null)
        }
    }

    @Test
    fun `maxHits bounds the hit list to the newest sessions`() {
        withFixture { fixture ->
            repeat(3) { index -> fixture.session("s-$index", "common-$index", createdAt = index.toLong() * 1000L) }
            val result = fixture.search("common", maxHits = 2)
            assertEquals(2, result.hits.size)
            assertEquals("s-2", result.hits.first().sessionId)
        }
    }

    @Test
    fun `out-of-bounds parameters fail closed`() {
        withFixture { fixture ->
            assertThrows("negative candidates") { fixture.search("x", maxCandidates = -1) }
            assertThrows("zero byte bound") { fixture.search("x", maxContentBytes = 0) }
            assertThrows("zero hits") { fixture.search("x", maxHits = 0) }
        }
    }

    // --- fixture ---------------------------------------------------------------

    private inline fun withFixture(block: (Fixture) -> Unit) {
        val root = File.createTempFile("helix-search-test", "root")
        assertTrue(root.delete())
        assertTrue(root.mkdirs())
        try {
            block(Fixture(root))
        } finally {
            root.deleteRecursively()
        }
    }

    private class Fixture(
        root: File,
    ) {
        val sessions = FakeSessionDao()
        val messages = FakeMessageDao(sessions)
        val store = FileContentStore(root)
        val repo = SessionSearchRepository(sessions, messages, store)

        fun session(
            id: String,
            title: String,
            createdAt: Long = 1000L,
            archivedAt: Long? = null,
        ): SessionEntity {
            val entity = SessionEntity(id, title, null, null, createdAt, archivedAt)
            sessions.insert(entity)
            return entity
        }

        fun body(
            sessionId: String,
            content: String,
        ) {
            val ref = store.write(content)
            messages.add(sessionId, ref.toStorageString())
        }

        /** A content reference whose body file was never written (missing or corrupted content). */
        fun orphanBody(sessionId: String) {
            val bytes = "orphan needle body".toByteArray()
            val hash = FileContentStore.sha256Hex(bytes)
            val ref = ContentRef(ContentRef.expectedPath(hash), bytes.size.toLong(), hash)
            messages.add(sessionId, ref.toStorageString())
        }

        fun search(
            query: String,
            maxCandidates: Int = 500,
            maxContentBytes: Int = 256 * 1024,
            maxHits: Int = 50,
        ): SessionSearchResult = repo.search(query, maxCandidates, maxContentBytes, maxHits)
    }

    /** Mirrors the DAO's list ordering: newest session first. */
    private class FakeSessionDao : SessionDao {
        private val rows = HashMap<String, SessionEntity>()

        override fun insert(session: SessionEntity) {
            rows[session.id] = session
        }

        override fun byId(id: String): SessionEntity? = rows[id]

        override fun list(): List<SessionEntity> = rows.values.sortedByDescending { it.createdAt }

        override fun archive(
            id: String,
            archivedAt: Long,
        ): Int =
            if (rows.containsKey(id)) {
                rows[id] = rows.getValue(id).copy(archivedAt = archivedAt)
                1
            } else {
                0
            }

        override fun restore(id: String): Int =
            if (rows.containsKey(id)) {
                rows[id] = rows.getValue(id).copy(archivedAt = null)
                1
            } else {
                0
            }

        override fun bindProvider(
            id: String,
            providerId: String,
            modelId: String,
        ): Int = error("not used")

        override fun selectModel(
            id: String,
            providerId: String,
            modelId: String,
        ): Int = error("not used")

        override fun updateDetails(
            id: String,
            title: String,
            directoryRef: String?,
        ): Int = error("not used")

        override fun deletePermanently(id: String): Int = if (rows.remove(id) != null) 1 else 0
    }

    /** Mirrors the DAO's join + ordering: newest session first, newest message first. */
    private class FakeMessageDao(
        private val sessions: FakeSessionDao,
    ) : MessageDao {
        private val rows = mutableListOf<MessageEntity>()
        private var nextSequence = 0L

        fun add(
            sessionId: String,
            contentRef: String,
        ) {
            rows += MessageEntity("m-$nextSequence", sessionId, null, "user", "TEXT", contentRef, nextSequence++)
        }

        override fun insert(message: MessageEntity) {
            rows += message
        }

        override fun byId(id: String): MessageEntity? = rows.firstOrNull { it.id == id }

        override fun listBySession(sessionId: String): List<MessageEntity> =
            rows.filter { it.sessionId == sessionId }.sortedBy { it.sequence }

        override fun maxSequence(sessionId: String): Long =
            rows.filter { it.sessionId == sessionId }.maxOfOrNull { it.sequence } ?: -1L

        override fun contentRefsBySession(sessionId: String): List<String> =
            rows.filter { it.sessionId == sessionId && it.contentRef != null }.mapNotNull { it.contentRef }

        override fun countByContentRef(contentRef: String): Int = rows.count { it.contentRef == contentRef }

        override fun contentSearchCandidates(limit: Int): List<MessageEntity> =
            rows
                .filter { it.contentRef != null }
                .sortedWith(
                    compareByDescending<MessageEntity> { sessions.byId(it.sessionId)?.createdAt ?: 0L }
                        .thenByDescending { it.sequence },
                ).take(limit)
    }
}
