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
            target.reads.clear()
            RestoreLibraryDocuments(target, journal).rollback()
            assertFalse(old.reference in target.reads)
            assertEquals(listOf(old.reference), target.references("notes"))
            assertTrue(target.references("originals/web").isEmpty())
            assertFalse(journal.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun publishedReceiptUsesActualNameAndNeverRetargetsToSameNameCopy() {
        val root = Files.createTempDirectory("library-identity").toFile()
        try {
            val target = Documents { name -> name.substringBeforeLast('.') + ".txt" }
            val journal = File(root, "journal.json")
            val publications = RestoreLibraryDocuments(target, journal)
            val saved = publications.save("notes", "note.md", byteArrayOf(1))
            val copy = target.save("notes", saved.name, saved.bytes)
            target.reads.clear()
            RestoreLibraryDocuments(target, journal).rollback()
            assertEquals(listOf(saved.reference), target.reads)
            assertEquals(listOf(copy.reference), target.references("notes"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun interruptedSaveUsesOwnedStemAndOriginalHashAfterProviderRenamesExtension() {
        val root = Files.createTempDirectory("library-intent").toFile()
        try {
            val target = Documents()
            val interrupted =
                object : DocumentStorage by target {
                    override fun save(
                        directory: String,
                        name: String,
                        bytes: ByteArray,
                        previous: DocumentStorage.File?,
                        mimeType: String,
                    ): DocumentStorage.File {
                        target.save(directory, name.substringBeforeLast('.') + " (1).txt", bytes)
                        error("Interrupted after publication")
                    }
                }
            val journal = File(root, "journal.json")
            assertThrows(IllegalStateException::class.java) {
                RestoreLibraryDocuments(interrupted, journal).save("notes", "note.md", byteArrayOf(1))
            }
            val saved = target.list("notes").single()
            val duplicate = target.save("notes", saved.name, saved.bytes)
            target.reads.clear()
            val recovery = RestoreLibraryDocuments(target, journal)
            assertThrows(IllegalArgumentException::class.java) { recovery.rollback() }
            assertTrue(target.reads.isEmpty())
            target.delete(duplicate)
            target.change(saved.reference, byteArrayOf(2))
            assertThrows(IllegalArgumentException::class.java) { recovery.rollback() }
            assertTrue(journal.exists())
            assertEquals(listOf(saved.reference), target.references("notes"))
            target.change(saved.reference, saved.bytes)
            recovery.rollback()
            assertTrue(target.references("notes").isEmpty())
            assertFalse(journal.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private class Documents(
        private val displayName: (String) -> String = { it },
    ) : DocumentStorage {
        private val values = linkedMapOf<String, ByteArray>()

        private val names = mutableMapOf<String, Pair<String, String>>()
        private var nextId = 1
        val reads = mutableListOf<String>()

        fun snapshot() = values.mapValues { DocumentStorage.hash(it.value) }

        fun change(
            reference: String,
            bytes: ByteArray,
        ) {
            check(reference in values)
            values[reference] = bytes
        }

        override fun list(directory: String) = references(directory).map { read(it, Int.MAX_VALUE) }

        override fun references(directory: String) = values.keys.filter { names.getValue(it).first == directory }

        override fun entries(directory: String) =
            references(directory).map {
                DocumentStorage.Entry(it, names.getValue(it).second)
            }

        override fun read(
            reference: String,
            maxBytes: Int,
        ): DocumentStorage.File {
            reads += reference
            val bytes = values.getValue(reference).copyOf()
            require(bytes.size <= maxBytes)
            return DocumentStorage.File(
                reference,
                names.getValue(reference).second,
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
            check(previous == null)
            val reference = (nextId++).toString()
            values[reference] = bytes.copyOf()
            names[reference] = directory to displayName(name)
            return read(reference, Int.MAX_VALUE)
        }

        override fun delete(previous: DocumentStorage.File) {
            check(values.remove(previous.reference) != null)
        }
    }
}
