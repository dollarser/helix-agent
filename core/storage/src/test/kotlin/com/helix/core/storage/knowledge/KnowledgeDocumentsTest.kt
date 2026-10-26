package com.helix.core.storage.knowledge

import com.helix.core.storage.content.DocumentStorage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Deterministic content authority tests. Platform publication is separately device-tested. */
class KnowledgeDocumentsTest {
    private val shared = MemoryDocuments()
    private val history = MemoryDocuments()
    private val store = KnowledgeStore(shared, history)

    @Test fun markdownIsTheOnlyNoteBodyAndReopensWithoutDatabase() =
        runBlocking {
            val body = "# 原文\n\n**正文**\n<!-- keep -->\n"
            store.saveNote(note(body))
            val file = shared.list("notes").single()
            assertTrue(file.bytes.toString(Charsets.UTF_8).endsWith(body))
            val reopened = KnowledgeStore(shared, history)
            reopened.refresh()
            assertEquals(
                body,
                reopened.notes.value
                    .single()
                    .body,
            )
            assertEquals(file.hash, reopened.note("note")!!.documentHash)
        }

    @Test fun externalEditIsReadAndStaleSaveCannotOverwriteIt() =
        runBlocking {
            store.saveNote(note("first"))
            val observed = store.note("note")!!
            val file = shared.list("notes").single()
            shared.save(
                "notes",
                file.name,
                file.bytes
                    .toString(Charsets.UTF_8)
                    .replace("\nfirst", "\nexternal")
                    .toByteArray(),
                file,
            )
            assertEquals("external", store.note("note")!!.body)
            expectFailure { store.saveNote(observed.copy(body = "stale")) }
            assertEquals("external", store.note("note")!!.body)
        }

    @Test fun externalRenameKeepsIdentityAndDeletionRemovesCatalogEntry() =
        runBlocking {
            store.saveNote(note("body"))
            val file = shared.list("notes").single()
            shared.save("notes", "renamed.md", file.bytes)
            shared.delete(file)
            store.refresh()
            assertEquals("body", store.note("note")!!.body)
            shared.delete(shared.list("notes").single())
            store.refresh()
            assertNull(store.note("note"))
            assertTrue(store.notes.value.isEmpty())
        }

    @Test fun duplicateIdentityFailsInsteadOfArbitrarilyChoosingOne() =
        runBlocking {
            store.saveNote(note("one"))
            shared.save("notes", "conflict.md", shared.list("notes").single().bytes)
            expectFailure { store.refresh() }
        }

    @Test fun failureDoesNotPublishSavedStateOrChangeBody() =
        runBlocking {
            store.saveNote(note("old"))
            val old = store.note("note")!!
            shared.failWrites = true
            expectFailure { store.saveNote(old.copy(body = "not saved")) }
            assertEquals("old", store.note("note")!!.body)
            assertEquals(
                "old",
                store.notes.value
                    .single()
                    .body,
            )
        }

    @Test fun privateConversationProvenanceAndAnswerHistoryNeverEnterSharedFiles() =
        runBlocking {
            store.saveNote(
                note(
                    "public body",
                ).copy(
                    originSessionId = "private-session",
                    originMessageId = "private-message",
                    originReferencesJson = "private-reference",
                    generatedDraft = "private-suggestion",
                    generationState = "RUNNING",
                ),
            )
            store.saveAnswer(
                KnowledgeAnswer("answer", "private question", 1, "READY", "private snapshots", "{}", "private answer"),
            )
            val publicText =
                shared
                    .list("notes")
                    .single()
                    .bytes
                    .toString(Charsets.UTF_8)
            assertFalse(publicText.contains("private-"))
            assertFalse(publicText.contains("private snapshots"))
            assertTrue(shared.list("answers").isEmpty())
            assertEquals("private-session", KnowledgeStore(shared, history).note("note")!!.originSessionId)
            assertEquals("private answer", KnowledgeStore(shared, history).answer("answer")!!.body)
        }

    @Test fun noteAndFrozenSourceEvidenceAreOnePublication() =
        runBlocking {
            val evidence = KnowledgeNoteSource("note", "source", "Frozen title", "", 1, "Old content", "hash", "READY")
            store.saveNote(note("body"), listOf(evidence))
            assertEquals(listOf(evidence), KnowledgeStore(shared, history).noteSources("note"))
            assertEquals(1, shared.list("notes").size)
        }

    @Test fun repeatedAnswerSaveAndRestartUseOneRecordedNoteIdentity() =
        runBlocking {
            store.saveAnswer(KnowledgeAnswer("answer", "Question", 1, "READY", "[]", "{}", "Answer"))
            val id = store.saveAnswerAsNote("answer", emptyList())
            assertEquals(id, KnowledgeStore(shared, history).saveAnswerAsNote("answer", emptyList()))
            assertEquals(1, shared.list("notes").size)
        }

    @Test fun answerSaveRecoversAfterNotePublicationBeforePrivateMarker() =
        runBlocking {
            store.saveAnswer(KnowledgeAnswer("answer", "Question", 1, "READY", "[]", "{}", "Answer"))
            history.failAt = 3
            expectFailure { store.saveAnswerAsNote("answer", emptyList()) }
            val published = shared.list("notes").single()
            history.failAt = null
            val reopened = KnowledgeStore(shared, history)
            val id = reopened.saveAnswerAsNote("answer", emptyList())
            assertEquals(published.reference, shared.list("notes").single().reference)
            assertEquals(id, reopened.answer("answer")!!.savedNoteId)
            assertEquals("", reopened.answer("answer")!!.pendingNoteId)
        }

    @Test fun explicitSaveAfterDeletionGetsNewIdentity() =
        runBlocking {
            store.saveAnswer(KnowledgeAnswer("answer", "Question", 1, "READY", "[]", "{}", "Answer"))
            val first = store.saveAnswerAsNote("answer", emptyList())
            store.removeNote(store.note(first)!!)
            val second = store.saveAnswerAsNote("answer", emptyList())
            assertFalse(first == second)
            assertEquals("Answer", store.note(second)!!.body)
        }

    @Test fun interruptedAnswerIsRecoveredPrivatelyAndNotReportedReady() =
        runBlocking {
            store.saveAnswer(KnowledgeAnswer("answer", "Question", 1, "RUNNING", "[]", "{}", "partial"))
            store.recoverAnswers()
            assertEquals("INTERRUPTED", store.answer("answer")!!.state)
            expectFailure { store.saveAnswerAsNote("answer", emptyList()) }
            assertTrue(shared.list("notes").isEmpty())
        }

    @Test fun staleDeleteDoesNotDeleteChangedContent() =
        runBlocking {
            store.saveNote(note("one"))
            val observed = store.note("note")!!
            store.saveNote(observed.copy(body = "two"))
            expectFailure { store.removeNote(observed) }
            assertEquals("two", store.note("note")!!.body)
        }

    @Test fun sourceSearchAndUpdatesReadFilesAndRejectStaleSource() =
        runBlocking {
            val original = store.insert(CollectedSource("source", "标题", 1, "share", "", "原始文字"))
            val updated = store.save(original.copy(extracted = "提取正文", state = "READY"))
            assertEquals("source", store.search("提取").single().id)
            expectFailure { store.save(original.copy(summary = "stale")) }
            assertEquals(updated, store.get("source"))
            assertTrue(store.search("\" OR *").isEmpty())
        }

    @Test fun malformedFormatAndOversizedIdentityDoNotBecomeEmptySuccess() =
        runBlocking {
            shared.save("notes", "broken.md", "unrecognized".toByteArray())
            expectFailure { store.refresh() }
            assertTrue(store.notes.value.isEmpty())
        }

    @Test fun metadataCannotTerminateTheMarkdownComment() {
        val encoded = KnowledgeDocumentCodec.note(note("# Body").copy(title = "--> <script>"), emptyList())
        assertEquals(1, encoded.substringBefore('\n').split("-->").size - 1)
        assertEquals("--> <script>", KnowledgeDocumentCodec.note(KnowledgeDocumentCodec.decode(encoded)).title)
    }

    private fun note(body: String) = KnowledgeNote("note", "Title", body, 1, 2)

    private suspend fun expectFailure(action: suspend () -> Unit) {
        try {
            action()
            fail("Expected explicit failure")
        } catch (
            _: IllegalArgumentException,
        ) {
        } catch (_: IllegalStateException) {
        }
    }

    private class MemoryDocuments : DocumentStorage {
        private val data = linkedMapOf<String, ByteArray>()
        var failWrites = false
        var failAt: Int? = null
        private var writes = 0

        override fun references(directory: String) = data.keys.filter { it.substringBeforeLast('/') == directory }

        override fun list(directory: String) = references(directory).map { read(it, 16 * 1024 * 1024) }

        override fun read(
            reference: String,
            maxBytes: Int,
        ): DocumentStorage.File {
            val bytes = requireNotNull(data[reference]).copyOf()
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
            check(!failWrites && writes != failAt) { "Injected storage failure" }
            val ref = "$directory/$name"
            check(if (previous == null) ref !in data else read(previous.reference, Int.MAX_VALUE).hash == previous.hash)
            data[ref] = bytes.copyOf()
            return read(ref, Int.MAX_VALUE)
        }

        override fun delete(previous: DocumentStorage.File) {
            check(read(previous.reference, Int.MAX_VALUE).hash == previous.hash)
            data.remove(previous.reference)
        }
    }
}
