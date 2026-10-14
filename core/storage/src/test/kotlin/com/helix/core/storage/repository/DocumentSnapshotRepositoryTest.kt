package com.helix.core.storage.repository

import com.helix.core.storage.content.ContentRef
import com.helix.core.storage.content.FileContentStore
import com.helix.core.storage.dao.DocumentSnapshotDao
import com.helix.core.storage.entity.DocumentSnapshotEntity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DocumentSnapshotRepositoryTest {
    private class Index : DocumentSnapshotDao {
        val rows = linkedMapOf<String, DocumentSnapshotEntity>()

        override fun insert(entity: DocumentSnapshotEntity) {
            check(rows.put(entity.id, entity) == null)
        }

        override fun find(
            sessionId: String,
            id: String,
        ) = rows[id]?.takeIf { it.sessionId == sessionId }

        override fun listBySession(sessionId: String) = rows.values.filter { it.sessionId == sessionId }.asReversed()

        override fun contentRefsBySession(sessionId: String) =
            rows.values
                .filter {
                    it.sessionId == sessionId
                }.map { it.contentRef }

        override fun countByContentRef(contentRef: String) = rows.values.count { it.contentRef == contentRef }
    }

    private fun fixture(block: (DocumentSnapshotRepository, Index, FileContentStore) -> Unit) {
        val root = Files.createTempDirectory("document-snapshot").toFile()
        val index = Index()
        val content = FileContentStore(root)
        try {
            block(DocumentSnapshotRepository(index, content) { it() }, index, content)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun sourceChangesDoNotMixVersionsAndRestartRetainsIdentity() =
        fixture { repository, index, content ->
            var source = "%PDF-first".toByteArray()
            val open = { _: String -> source.inputStream() }
            val old = repository.create("s", "scope:ws:a.pdf", open) { }
            source = "%PDF-second".toByteArray()
            val resumed = DocumentSnapshotRepository(index, FileContentStore(content.root)) { it() }
            assertEquals(old, resumed.resolve("s", old.id, open) { })
            assertEquals("%PDF-first", resumed.file("s", old.id) { }.readText())
            val fresh = repository.create("s", "scope:ws:a.pdf", open) { }
            assertNotEquals(old.id, fresh.id)
            assertNotEquals(old.contentRef, fresh.contentRef)
        }

    @Test fun crossSessionAndRevokedSourceCannotUseCachedBytes() =
        fixture { repository, _, _ ->
            val snapshot = repository.create("s", "scope:ws:a.pdf", { "%PDF-data".byteInputStream() }) { }
            assertThrows(DocumentSnapshotRepository.Unavailable::class.java) {
                repository.resolve("other", snapshot.id, { error("Must not open") }) { }
            }
            assertThrows(SecurityException::class.java) {
                repository.resolve("s", snapshot.id, { throw SecurityException("revoked") }) { }
            }
        }

    @Test fun missingOrTamperedBytesExpireRatherThanReopenOriginal() =
        fixture { repository, _, content ->
            val snapshot = repository.create("s", "scope:ws:a.pdf", { "%PDF-data".byteInputStream() }) { }
            content.delete(ContentRef.parse(snapshot.contentRef))
            assertEquals(
                "REF_EXPIRED",
                assertThrows(DocumentSnapshotRepository.Unavailable::class.java) {
                    repository.resolve("s", snapshot.id, { "%PDF-new".byteInputStream() }) { }
                }.code,
            )
        }

    @Test fun malformedInputsAndCancellationNeverPublishBindings() =
        fixture { repository, index, _ ->
            for ((path, bytes) in listOf(
                "scope:ws:a.pdf" to "notpdf",
                "scope:ws:a.doc" to "%PDF-data",
                "scope:ws:a.docx" to "%PDF-data",
            )) {
                assertThrows(DocumentSnapshotRepository.Unavailable::class.java) {
                    repository.create("s", path, { bytes.byteInputStream() }) { }
                }
            }
            assertThrows(IllegalStateException::class.java) {
                repository.create("s", "scope:ws:a.pdf", { "%PDF-data".byteInputStream() }) { error("cancelled") }
            }
            assertTrue(index.rows.isEmpty())
        }
}
