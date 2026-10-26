package com.helix.core.storage.knowledge

import com.helix.core.storage.content.DocumentStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class KnowledgeLibraryRestoreTest {
    private val source = TestDocuments()
    private val target = TestDocuments()
    private val history = TestDocuments()
    private val store = KnowledgeStore(target, history)
    private val note = KnowledgeNote("stable-note", "Title", "# Preserved", 1, 2, kind = "KNOWLEDGE")

    private fun fixture(attachment: Boolean = true) {
        source.save("notes", "renamed.md", KnowledgeDocumentCodec.note(note, emptyList()).toByteArray())
        val item =
            CollectedSource(
                "stable-source",
                "Original",
                3,
                "share",
                "",
                "raw",
                filesJson =
                    if (attachment) {
                        """[{"name":"document.txt","mime":"text/plain","reference":"old-row-42"}]"""
                    } else {
                        "[]"
                    },
                state = "READY",
                extracted = "extracted",
                summary = "summary",
            )
        source.save("sources", "source.md", KnowledgeDocumentCodec.source(item).toByteArray())
        if (attachment) source.save("originals/stable-source", "0.txt", "original bytes".toByteArray())
    }

    @Test fun restoresStableIdentityOriginalsAndRebuildsIndexWithoutPrivateHistory() =
        runBlocking {
            fixture()
            source.save("answers", "private-history.md", "must not be imported".toByteArray())
            val before = source.snapshot()
            assertEquals(2, store.restoreLibrary(source))
            val reopened = KnowledgeStore(target, TestDocuments())
            reopened.refresh()
            assertEquals(
                note.id,
                reopened.notes.value
                    .single()
                    .id,
            )
            assertEquals(note.body, reopened.searchNotes("Preserved").single().body)
            val restored = reopened.get("stable-source")!!
            assertTrue(restored.filesJson.contains("originals/stable-source/0.txt"))
            assertFalse(restored.filesJson.contains("old-row-42"))
            assertEquals(
                "original bytes",
                target
                    .list("originals/stable-source")
                    .single()
                    .bytes
                    .toString(Charsets.UTF_8),
            )
            assertEquals(before, source.snapshot())
            assertTrue(history.snapshot().isEmpty())
            assertTrue(target.list("answers").isEmpty())
        }

    @Test fun retryDoesNotDuplicateAlreadyRestoredRecords() =
        runBlocking {
            fixture()
            store.restoreLibrary(source)
            val before = target.snapshot()
            assertEquals(2, store.restoreLibrary(source))
            assertEquals(before, target.snapshot())
        }

    @Test fun semanticallyIdenticalSourceKeepsExternalFormattingOnRetry() =
        runBlocking {
            fixture()
            store.restoreLibrary(source)
            val file = target.list("sources").single()
            val formatted = file.bytes.toString(Charsets.UTF_8).replace("\"format\":\"1\"", "\"format\": \"1\"")
            target.replace(file.reference, formatted.toByteArray())
            val before = target.snapshot()
            assertEquals(2, store.restoreLibrary(source))
            assertEquals(before, target.snapshot())
        }

    @Test fun providerAddedMimeExtensionDoesNotChangeAttachmentIdentityOnRetry() =
        runBlocking {
            fixture()
            target.appendExtension = true
            store.restoreLibrary(source)
            val before = target.snapshot()
            assertEquals(2, store.restoreLibrary(source))
            assertEquals(before, target.snapshot())
            assertEquals(1, target.list("originals/stable-source").size)
        }

    @Test fun providerReplacedMimeExtensionKeepsTheSameSlotAndRejectsDifferentBytes() =
        runBlocking {
            fixture()
            target.replaceExtension = true
            store.restoreLibrary(source)
            val before = target.snapshot()
            assertEquals(2, store.restoreLibrary(source))
            assertEquals(before, target.snapshot())
            val original = target.references("originals/stable-source").single()
            target.replace(original, "external edit".toByteArray())
            val edited = target.snapshot()
            rejects { store.restoreLibrary(source) }
            assertEquals(edited, target.snapshot())
        }

    @Test fun conflictingDestinationRejectsBeforeCopyingAnyAttachments() =
        runBlocking {
            fixture()
            store.saveNote(note.copy(body = "different"))
            val before = target.snapshot()
            rejects { store.restoreLibrary(source) }
            assertEquals(before, target.snapshot())
        }

    @Test fun missingAttachmentAndInvalidDirectoryAreNotEmptySuccess() =
        runBlocking {
            fixture()
            source.delete(source.list("originals/stable-source").single())
            rejects { store.restoreLibrary(source) }
            assertTrue(target.snapshot().isEmpty())
            rejects { store.restoreLibrary(TestDocuments()) }
        }

    @Test fun lostPermissionBeforeVerificationPublishesNothing() =
        runBlocking {
            fixture()
            source.onRead = { source.allowReads = false }
            try {
                store.restoreLibrary(source)
                error("Expected permission failure")
            } catch (_: SecurityException) {
                assertTrue(target.snapshot().isEmpty())
            }
        }

    @Test fun changedSourceBeforePublicationIsRejected() =
        runBlocking {
            fixture(false)
            source.onRead = { path ->
                if (path == "notes/renamed.md") {
                    source.replace(path, "changed after listing".toByteArray())
                }
            }
            rejects { store.restoreLibrary(source) }
            assertTrue(target.snapshot().isEmpty())
        }

    @Test fun interruptedPublicationCanResumeWithoutOverwritingOrDuplicating() =
        runBlocking {
            fixture()
            target.failAt = 2
            rejects { store.restoreLibrary(source) }
            assertEquals(1, target.snapshot().size)
            target.failAt = null
            assertEquals(2, store.restoreLibrary(source))
            assertEquals(3, target.snapshot().size)
        }

    @Test fun cancellationDuringPreflightDoesNotPublish() =
        runBlocking {
            fixture()
            val job =
                launch {
                    source.onRead = { coroutineContext[Job]!!.cancel(CancellationException("fixture cancel")) }
                    store.restoreLibrary(source)
                }
            job.join()
            assertTrue(job.isCancelled)
            assertTrue(target.snapshot().isEmpty())
        }

    @Test fun oversizedNoteCannotPublishAnUnreadableCatalog() =
        runBlocking {
            val huge = note.copy(body = "a".repeat(4 * 1024 * 1024))
            source.save("notes", "huge.md", KnowledgeDocumentCodec.note(huge, emptyList()).toByteArray())
            rejects { store.restoreLibrary(source) }
            assertTrue(target.snapshot().isEmpty())
        }

    @Test fun interruptedSourceWorkIsNotAutomaticallyResumedAsAnEffect() =
        runBlocking {
            fixture(false)
            val file = source.list("sources").single()
            source.replace(
                file.reference,
                file.bytes
                    .toString(Charsets.UTF_8)
                    .replace("READY", "DELETING")
                    .toByteArray(),
            )
            store.restoreLibrary(source)
            assertEquals("INCOMPLETE", store.get("stable-source")!!.state)
            assertTrue(store.unfinished().isEmpty())
        }

    @Test fun originalsUseBinaryReadLimitRatherThanTextCatalogLimit() =
        runBlocking {
            fixture()
            source.replace("originals/stable-source/0.txt", ByteArray(5 * 1024 * 1024) { 1 })
            target.listedBytes = 4 * 1024 * 1024
            assertEquals(2, store.restoreLibrary(source))
            val original = target.references("originals/stable-source").single()
            assertEquals(5 * 1024 * 1024, target.read(original, 16 * 1024 * 1024).bytes.size)
            assertEquals(2, store.restoreLibrary(source))
        }

    private suspend fun rejects(action: suspend () -> Unit) {
        try {
            action()
            fail("Expected restore failure")
        } catch (_: IllegalArgumentException) {
        } catch (_: IllegalStateException) {
        }
    }

    private class TestDocuments : DocumentStorage {
        private val files = linkedMapOf<String, ByteArray>()
        var failAt: Int? = null
        var allowReads = true
        var appendExtension = false
        var replaceExtension = false
        var listedBytes = 16 * 1024 * 1024
        var onRead: ((String) -> Unit)? = null
        private var writes = 0

        fun snapshot() = files.mapValues { DocumentStorage.hash(it.value) }

        fun replace(
            path: String,
            bytes: ByteArray,
        ) {
            files[path] = bytes.copyOf()
        }

        override fun references(directory: String) = files.keys.filter { it.substringBeforeLast('/') == directory }

        override fun list(directory: String) = references(directory).map { file(it, listedBytes) }

        override fun read(
            reference: String,
            maxBytes: Int,
        ): DocumentStorage.File {
            onRead?.invoke(reference)
            return file(reference, maxBytes)
        }

        private fun file(
            reference: String,
            maxBytes: Int,
        ): DocumentStorage.File {
            if (!allowReads) throw SecurityException("fixture revoked")
            val bytes = requireNotNull(files[reference]).copyOf()
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
            writes++
            check(writes != failAt)
            val actual =
                when {
                    replaceExtension && directory.startsWith("originals/") -> name.substringBefore('.') + ".bin"
                    appendExtension && directory.startsWith("originals/") -> "$name.txt"
                    else -> name
                }
            val path = "$directory/$actual"
            check(previous == null && path !in files)
            files[path] = bytes.copyOf()
            return file(path, Int.MAX_VALUE)
        }

        override fun delete(previous: DocumentStorage.File) {
            files.remove(previous.reference)
        }
    }
}
