package com.helix.core.storage.repository

import com.helix.core.storage.content.FileContentStore
import com.helix.core.storage.dao.MessageDao
import com.helix.core.storage.entity.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MessageRepositoryTest {
    private lateinit var tempDir: File
    private lateinit var contentStore: FileContentStore
    private lateinit var dao: InMemoryMessageDao
    private lateinit var repository: MessageRepository

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("msg-repo-test").toFile()
        contentStore = FileContentStore(tempDir)
        dao = InMemoryMessageDao()
        repository = MessageRepository(dao, contentStore)
    }

    @Test
    fun `regenerateLatest supersedes target assistant and subsequent messages`() {
        repository.append("m1", "s1", "t1", "USER", "TEXT", "Hello")
        val asst1 = repository.append("m2", "s1", "t1", "ASSISTANT", "TEXT", "First answer")

        assertEquals(asst1, repository.latestAssistant("s1"))

        repository.regenerateLatest("s1", "m2", "req-regen-1")

        val reloadedUser = repository.resolve("m1")
        val reloadedAsst = repository.resolve("m2")

        assertNull("user message should not be superseded", reloadedUser.supersededBy)
        assertEquals("req-regen-1", reloadedAsst.supersededBy)
        assertNull("no active assistant should remain", repository.latestAssistant("s1"))
    }

    @Test
    fun `regenerateLatest fails when target is not found`() {
        assertThrows(IllegalArgumentException::class.java) {
            repository.regenerateLatest("s1", "nonexistent", "req-1")
        }
    }

    @Test
    fun `regenerateLatest fails when session mismatches`() {
        repository.append("m1", "s1", "t1", "ASSISTANT", "TEXT", "Hello")
        assertThrows(IllegalArgumentException::class.java) {
            repository.regenerateLatest("other-session", "m1", "req-1")
        }
    }

    @Test
    fun `regenerateLatest fails when target is user message`() {
        repository.append("m1", "s1", "t1", "USER", "TEXT", "Hello")
        assertThrows(IllegalArgumentException::class.java) {
            repository.regenerateLatest("s1", "m1", "req-1")
        }
    }

    @Test
    fun `regenerateLatest fails when target is already superseded`() {
        repository.append("m1", "s1", "t1", "ASSISTANT", "TEXT", "Hello")
        repository.supersedeFrom("s1", 0L, "old-req")

        assertThrows(IllegalArgumentException::class.java) {
            repository.regenerateLatest("s1", "m1", "req-2")
        }
    }

    @Test
    fun `regenerateLatest fails when target is not the latest assistant message`() {
        val asst1 = repository.append("m1", "s1", "t1", "ASSISTANT", "TEXT", "Old answer")
        val asst2 = repository.append("m2", "s1", "t2", "ASSISTANT", "TEXT", "New answer")

        assertEquals(asst2, repository.latestAssistant("s1"))

        // Trying to regenerate m1 (which is not latest assistant) fails closed
        assertThrows(IllegalArgumentException::class.java) {
            repository.regenerateLatest("s1", "m1", "req-1")
        }

        // Neither message was superseded
        assertNull(repository.resolve("m1").supersededBy)
        assertNull(repository.resolve("m2").supersededBy)
    }

    private class InMemoryMessageDao : MessageDao {
        private val rows = mutableListOf<MessageEntity>()

        override fun insert(message: MessageEntity) {
            rows += message
        }

        override fun retainedByKindAfter(
            kind: String,
            afterId: String?,
            limit: Int,
        ): List<MessageEntity> =
            rows
                .filter {
                    it.kind == kind && (afterId == null || it.id > afterId)
                }.sortedBy { it.id }
                .take(limit)

        override fun byId(id: String): MessageEntity? = rows.firstOrNull { it.id == id }

        override fun listBySession(sessionId: String): List<MessageEntity> =
            rows.filter { it.sessionId == sessionId && it.supersededBy == null }.sortedBy { it.sequence }

        override fun pageAfter(
            sessionId: String,
            after: Long,
            limit: Int,
        ): List<MessageEntity> =
            rows
                .filter { it.sessionId == sessionId && it.supersededBy == null && it.sequence > after }
                .sortedBy { it.sequence }
                .take(limit)

        override fun latestOfKind(
            sessionId: String,
            kind: String,
            includeSuperseded: Boolean,
        ): MessageEntity? =
            rows
                .filter {
                    it.sessionId == sessionId &&
                        (includeSuperseded || it.supersededBy == null) &&
                        it.kind == kind
                }.maxByOrNull { it.sequence }

        override fun latestUser(sessionId: String): MessageEntity? =
            rows
                .filter { it.sessionId == sessionId && it.role == "USER" && it.supersededBy == null }
                .maxByOrNull { it.sequence }

        override fun latestAssistant(sessionId: String): MessageEntity? =
            rows
                .filter { it.sessionId == sessionId && it.role == "ASSISTANT" && it.supersededBy == null }
                .maxByOrNull { it.sequence }

        override fun supersedeFrom(
            sessionId: String,
            from: Long,
            requestId: String,
        ) {
            rows.replaceAll {
                if (it.sessionId == sessionId && it.sequence >= from && it.supersededBy == null) {
                    it.copy(supersededBy = requestId)
                } else {
                    it
                }
            }
        }

        override fun allRevisions(sessionId: String): List<MessageEntity> =
            rows.filter { it.sessionId == sessionId }.sortedBy { it.sequence }

        override fun supersededTurns(sessionId: String): List<String> =
            rows
                .filter { it.sessionId == sessionId && it.supersededBy != null }
                .mapNotNull { it.turnId }
                .distinct()

        override fun supersededRequest(turnId: String): String? =
            rows.firstOrNull { it.turnId == turnId && it.supersededBy != null }?.supersededBy

        override fun maxSequence(sessionId: String): Long =
            rows.filter { it.sessionId == sessionId }.maxOfOrNull { it.sequence } ?: -1L

        override fun contentRefsBySession(sessionId: String): List<String> =
            rows.filter { it.sessionId == sessionId }.mapNotNull { it.contentRef }

        override fun countByContentRef(contentRef: String): Int = rows.count { it.contentRef == contentRef }

        override fun contentSearchCandidates(limit: Int): List<MessageEntity> =
            rows
                .filter { it.contentRef != null && it.supersededBy == null }
                .sortedByDescending { it.sequence }
                .take(limit)
    }
}
