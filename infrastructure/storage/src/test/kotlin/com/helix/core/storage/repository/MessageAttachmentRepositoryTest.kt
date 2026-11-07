package com.helix.core.storage.repository

import com.helix.core.storage.assertThrows
import com.helix.core.storage.dao.MessageAttachmentDao
import com.helix.core.storage.entity.MessageAttachmentEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** HXA-049 (ADR-0014): [MessageAttachmentRepository] — closed validation of a message's bindings. */
class MessageAttachmentRepositoryTest {
    private val sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test
    fun bindAssignsOrdinalsInListOrderAndPersistsAllFields() {
        val dao = FakeMessageAttachmentDao()
        MessageAttachmentRepository(dao).bind("msg-1", listOf(binding("art-1"), binding("art-2")))
        val inserted = dao.inserted
        assertEquals(2, inserted.size)
        assertEquals(0, inserted[0].ordinal)
        assertEquals(1, inserted[1].ordinal)
        assertEquals("msg-1", inserted[0].messageId)
        assertEquals("art-1", inserted[0].artifactId)
        assertEquals("REFERENCE", inserted[0].purpose)
        assertEquals(sha, inserted[0].boundSha256)
    }

    @Test
    fun bindFailsClosedBeforeAnyInsert() {
        val dao = FakeMessageAttachmentDao()
        val repo = MessageAttachmentRepository(dao)
        assertThrows("over the closed per-message limit") { repo.bind("msg-1", List(5) { binding("art-$it") }) }
        assertThrows("blank messageId") { repo.bind("  ", listOf(binding())) }
        assertThrows("blank purpose") { repo.bind("msg-1", listOf(binding(purpose = ""))) }
        assertThrows("blank artifactId") { repo.bind("msg-1", listOf(binding(artifactId = ""))) }
        assertThrows("bad hash length") { repo.bind("msg-1", listOf(binding(hash = "zzz"))) }
        assertThrows("uppercase hash") { repo.bind("msg-1", listOf(binding(hash = sha.uppercase()))) }
        assertEquals("a rejected bind must not insert any row", 0, dao.inserted.size)
    }

    @Test
    fun listByMessageReturnsOrdinalAscending() {
        val dao = FakeMessageAttachmentDao()
        dao.inserted += MessageAttachmentEntity(2L, "msg-1", "art-2", 1, "REFERENCE", sha)
        dao.inserted += MessageAttachmentEntity(1L, "msg-1", "art-1", 0, "REFERENCE", sha)
        val listed = MessageAttachmentRepository(dao).listByMessage("msg-1")
        assertEquals(listOf("art-1", "art-2"), listed.map { it.artifactId })
    }

    private fun binding(
        artifactId: String = "art-1",
        purpose: String = "REFERENCE",
        hash: String = sha,
    ) = MessageAttachmentRepository.Binding(artifactId, purpose, hash)

    private class FakeMessageAttachmentDao : MessageAttachmentDao {
        val inserted = mutableListOf<MessageAttachmentEntity>()

        override fun insert(attachment: MessageAttachmentEntity) {
            inserted += attachment
        }

        override fun listByMessage(messageId: String): List<MessageAttachmentEntity> =
            inserted.filter { it.messageId == messageId }.sortedBy { it.ordinal }

        override fun deleteByMessage(messageId: String) {
            inserted.removeAll { it.messageId == messageId }
        }
    }
}
