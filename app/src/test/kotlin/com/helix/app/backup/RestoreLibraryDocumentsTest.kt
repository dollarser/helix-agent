package com.helix.app.backup

import com.helix.core.storage.content.DocumentStorage
import com.helix.core.storage.knowledge.CollectedSource
import com.helix.core.storage.knowledge.KnowledgeStore
import com.helix.core.storage.knowledge.PortableKnowledgeRestore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RestoreLibraryDocumentsTest {
    @Test fun webOriginalKeepsIdentityAcrossRetryAndExternalEditIsNotOverwritten() =
        runBlocking {
            val root = Files.createTempDirectory("library-journal").toFile()
            try {
                val source = Documents()
                val target = Documents()
                val item = CollectedSource("web", "Synthetic page", 1, "web", "https://example.invalid", "Page")
                KnowledgeStore(source, Documents()).insert(item)
                source.save("originals/web", "public-page.html", "<p>Page</p>".toByteArray())
                val journal = File(root, "journal.json")
                val publications = RestoreLibraryDocuments(target, journal)
                assertEquals(1, PortableKnowledgeRestore.publish(source, publications, true))
                val first = target.snapshot()
                assertEquals(1, PortableKnowledgeRestore.publish(source, publications, true))
                assertEquals(first, target.snapshot())
                val original = target.list("originals/web").single()
                assertTrue(original.name.startsWith("public-page.restore-"))
                target.change(original.reference, "External edit".toByteArray())
                val edited = target.snapshot()
                assertThrows(IllegalStateException::class.java) {
                    runBlocking { PortableKnowledgeRestore.preflight(source, publications, true) }
                }
                assertEquals(edited, target.snapshot())
                assertThrows(IllegalArgumentException::class.java) { publications.rollback() }
                assertEquals("External edit", target.read(original.reference, 100).bytes.decodeToString())
            } finally {
                root.deleteRecursively()
            }
        }

    @Test fun rollbackRemovesOnlyOwnedUnchangedPublications() {
        val root = Files.createTempDirectory("library-rollback").toFile()
        try {
            val target = Documents()
            val old = target.save("notes", "unrelated.md", "Existing".toByteArray())
            val journal = File(root, "journal.json")
            val publications = RestoreLibraryDocuments(target, journal)
            publications.save("notes", "synthetic.md", "New".toByteArray())
            publications.save("originals/web", "public-page.html", "HTML".toByteArray())
            publications.rollback()
            assertEquals(listOf(old.reference), target.references("notes"))
            assertTrue(target.references("originals/web").isEmpty())
            assertFalse(journal.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private class Documents : DocumentStorage {
        private val values = linkedMapOf<String, ByteArray>()

        fun snapshot() = values.mapValues { DocumentStorage.hash(it.value) }

        fun change(
            reference: String,
            bytes: ByteArray,
        ) {
            check(reference in values)
            values[reference] = bytes
        }

        override fun list(directory: String) = references(directory).map { read(it, Int.MAX_VALUE) }

        override fun references(directory: String) = values.keys.filter { it.substringBeforeLast('/') == directory }

        override fun read(
            reference: String,
            maxBytes: Int,
        ): DocumentStorage.File {
            val bytes = values.getValue(reference).copyOf()
            require(bytes.size <= maxBytes)
            return DocumentStorage.File(
                reference,
                reference.substringAfterLast('/'),
                bytes,
                DocumentStorage.hash(bytes),
            )
        }

        override fun save(
            directory: String,
            name: String,
            bytes: ByteArray,
            previous: DocumentStorage.File?,
            mimeType: String,
        ): DocumentStorage.File {
            check(previous == null && "$directory/$name" !in values)
            values["$directory/$name"] = bytes.copyOf()
            return read("$directory/$name", Int.MAX_VALUE)
        }

        override fun delete(previous: DocumentStorage.File) {
            check(values.remove(previous.reference) != null)
        }
    }
}
