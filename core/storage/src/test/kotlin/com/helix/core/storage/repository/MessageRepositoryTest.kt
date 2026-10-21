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
    private lateinit var search: SearchProjectionFixture

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("msg-repo-test").toFile()
        contentStore = FileContentStore(tempDir)
        dao = InMemoryMessageDao()
        search = SearchProjectionFixture()
        repository = MessageRepository(dao, contentStore, search, { it() }, { 42L })
    }

    @Test fun manualEditRetainsOldContentAndUpdatesOnlyItsSearchProjection() {
        val original = repository.append("user", "s", null, "USER", "TEXT", "before")
        repository.append("answer", "s", null, "ASSISTANT", "TEXT", "unchanged answer")
        val edited = repository.editUserText("s", "user", original.contentRef, "after", "evidence")
        assertEquals("after", repository.readContent(edited))
        assertEquals("before", repository.readContent(repository.resolve("evidence")))
        assertEquals("SYSTEM", repository.resolve("evidence").role)
        assertEquals("after", search.bodies.getValue("user").body)
        assertEquals(setOf("user", "answer"), search.bodies.keys)
        assertEquals(original.sequence, edited.sequence)
        assertThrows(IllegalArgumentException::class.java) {
            repository.editUserText("s", "user", original.contentRef, "stale", "stale-evidence")
        }
        assertEquals("after", repository.readContent(repository.resolve("user")))
    }

    @Test fun visibleTextPublishesSearchAndActivityButProtocolDoesNot() {
        val message = repository.append("visible", "s", "t", "USER", "TEXT", "中文 Needle")
        repository.append("protocol", "s", "t", "ASSISTANT", "TOOL_CALLS", "private protocol")
        repository.append("blank", "other", null, "USER", "TEXT", "")
        assertEquals(42L, message.createdAt)
        assertEquals(setOf("visible"), search.bodies.keys)
        assertEquals(message.contentRef, search.bodies.getValue("visible").contentRef)
        assertEquals("中文 needle", search.bodies.getValue("visible").folded)
        assertEquals(mapOf("s" to 42L, "other" to 42L), search.activity)
    }

    @Test fun copiedHistoryKeepsOriginalTimeAndSearchBodyWithoutReusingExecutionOrBumpingActivity() {
        val source = repository.append("source", "original", "turn", "ASSISTANT", "TEXT", "Branch evidence")
        val copied = repository.copyHistory(source, "copy", "branch")
        assertEquals(source.createdAt, copied.createdAt)
        assertEquals(source.contentRef, copied.contentRef)
        assertNull(copied.turnId)
        assertEquals("branch", search.bodies.getValue("copy").sessionId)
        assertNull(search.activity["branch"])
    }

    @Test fun pagingKeepsEffectiveOrderWhileTurnLookupCanReadAnOldResult() {
        repeat(150) { repository.append("m$it", "s", "t$it", "ASSISTANT", "TEXT", "Answer $it") }
        repository.supersedeFrom("s", 148, "revision")
        assertEquals(listOf(145L, 146L, 147L), repository.recentBySession("s", 3).map { it.sequence })
        assertEquals(listOf(9L, 10L), repository.recentBySession("s", 2, 10).map { it.sequence })
        assertEquals("Answer 1", repository.readContent(repository.byTurn("t1").single()))
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

        override fun replaceUserContent(
            id: String,
            sessionId: String,
            expected: String?,
            replacement: String,
        ): Int {
            val index =
                rows.indexOfFirst {
                    it.id == id && it.sessionId == sessionId && it.role == "USER" &&
                        it.kind == "TEXT" &&
                        it.supersededBy == null &&
                        it.contentRef == expected
                }
            if (index < 0) return 0
            rows[index] = rows[index].copy(contentRef = replacement)
            return 1
        }

        override fun invalidateDerivedKind(
            sessionId: String,
            kind: String,
            editId: String,
        ) {
            rows.replaceAll {
                if (it.sessionId == sessionId && it.kind == kind &&
                    it.supersededBy == null
                ) {
                    it.copy(supersededBy = editId)
                } else {
                    it
                }
            }
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

        override fun recentBySession(
            sessionId: String,
            limit: Int,
            through: Long,
        ) = listBySession(sessionId)
            .filter {
                it.sequence <=
                    through
            }.takeLast(limit)
            .reversed()

        override fun byTurn(turnId: String) =
            rows
                .filter {
                    it.turnId == turnId && it.supersededBy == null
                }.sortedBy { it.sequence }
    }
}
