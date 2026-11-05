package com.helix.app.knowledge

import com.helix.core.storage.content.DocumentStorage
import com.helix.core.storage.knowledge.KnowledgeNote
import com.helix.core.storage.knowledge.KnowledgeStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeReferenceCatalogTest {
    @Test fun emptyQueryLoadsPersistedDocumentsBeforeTreatingTheLibraryAsEmpty() =
        runBlocking {
            val documents = Documents()
            val history = Documents()
            KnowledgeStore(documents, history).saveNote(KnowledgeNote("note", "Persisted title", "User content", 1, 2))
            val cold = KnowledgeStore(documents, history)
            assertTrue(cold.notes.value.isEmpty())
            val candidates = KnowledgeReferenceService(cold).candidates("")
            assertEquals(listOf("note"), candidates.map { it.id })
            assertEquals("Persisted title", candidates.single().title)
            assertEquals(listOf("note"), cold.notes.value.map { it.id })
        }

    private class Documents : DocumentStorage {
        private val files = linkedMapOf<String, DocumentStorage.File>()

        override fun references(directory: String) = files.keys.filter { it.substringBeforeLast('/') == directory }

        override fun list(directory: String) = references(directory).map { read(it, Int.MAX_VALUE) }

        override fun read(
            reference: String,
            maxBytes: Int,
        ): DocumentStorage.File =
            files.getValue(reference).also {
                require(it.bytes.size <= maxBytes)
            }

        override fun save(
            directory: String,
            name: String,
            bytes: ByteArray,
            previous: DocumentStorage.File?,
            mimeType: String,
        ): DocumentStorage.File {
            val reference = previous?.reference ?: "$directory/$name"
            check(previous == null || files[reference]?.hash == previous.hash)
            return DocumentStorage.File(reference, name, bytes.copyOf(), DocumentStorage.hash(bytes)).also {
                files[reference] = it
            }
        }

        override fun delete(previous: DocumentStorage.File) {
            check(files.getValue(previous.reference).hash == previous.hash)
            files.remove(previous.reference)
        }
    }
}
