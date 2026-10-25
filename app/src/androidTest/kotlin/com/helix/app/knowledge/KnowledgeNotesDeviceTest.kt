package com.helix.app.knowledge

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.chat.EgressDisclosure
import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ProviderResidence
import com.helix.core.storage.knowledge.KnowledgeStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class KnowledgeNotesDeviceTest {
    @Test fun sourceCandidateDoesNotSaveWhenItsEvidenceChanges() =
        fixture { library, store ->
            val source = collectSources(library).first().let { requireNotNull(store.get(it)) }
            store.save(source.copy(extracted = "Changed evidence"))
            var refused = false
            try {
                library.noteService.createFromSources(listOf(source), "Conclusion", "Edited conclusion")
            } catch (
                _: IllegalStateException,
            ) {
                refused = true
            }
            assertTrue(refused)
            assertTrue(store.notes.first().isEmpty())
            assertEquals("Changed evidence", store.get(source.id)?.extracted)
        }

    @Test fun replySavingIsIdempotentAndNeverOverwritesLaterUserEdits() =
        fixture { library, store ->
            val id =
                library.noteService.saveReply(
                    "session",
                    "message",
                    "Conversation note",
                    "Original reply",
                    emptyList(),
                )
            library.noteService.save(id, "Edited title", "User edited body", false)
            val repeated =
                library.noteService.saveReply(
                    "session",
                    "message",
                    "Conversation note",
                    "Original reply",
                    emptyList(),
                )
            assertEquals(id, repeated)
            val saved = requireNotNull(store.note(id))
            assertEquals("User edited body", saved.body)
            assertEquals("NOTE", saved.kind)
            assertEquals("DRAFT", saved.state)
            assertEquals("session", saved.originSessionId)
            assertEquals("message", saved.originMessageId)
            assertEquals(knowledgeHash("Original reply"), saved.originContentHash)
            assertEquals(1, store.notes.first().size)
        }

    @Test
    fun selectedVersionRequiresExplicitKeepAndDeletionCannotBeBypassed() =
        fixture { library, store ->
            val id = createStoredTestNote(store, emptyList(), "Synthetic note")
            library.noteService.save(id, "Synthetic note", "Original text", false)
            val snapshot = library.references.select("NOTE", id, "FULL")
            assertTrue(library.references.valid(listOf(snapshot)))
            library.noteService.save(id, "Synthetic note", "Updated text", true)
            assertEquals(KnowledgeReferenceService.State.CHANGED, library.references.state(snapshot))
            assertFalse(library.references.valid(listOf(snapshot)))
            assertTrue(library.references.valid(listOf(snapshot.copy(keepVersion = true))))
            assertEquals("Original text", snapshot.text)
            library.noteService.management.remove(requireNotNull(store.note(id)))
            assertEquals(KnowledgeReferenceService.State.UNAVAILABLE, library.references.state(snapshot))
            assertFalse(library.references.valid(listOf(snapshot.copy(keepVersion = true))))
        }

    @Test
    fun knowledgeIsExplicitNewUnreviewedContentWithFrozenOrigins() =
        fixture { library, store ->
            val ids = collectSources(library)
            val id = createStoredTestNote(store, ids, "Source note")
            library.noteService.save(id, "Source note", "User authored observations", true)
            val original = requireNotNull(store.note(id))
            val knowledgeId = library.noteService.createKnowledge(original, "Conclusion", "Edited conclusion")
            val knowledge = requireNotNull(store.note(knowledgeId))
            assertEquals("NOTE", original.kind)
            assertEquals("KNOWLEDGE", knowledge.kind)
            assertEquals("DRAFT", knowledge.state)
            assertEquals(original, store.note(id))
            val origin =
                com.helix.core.storage.repository.KnowledgeReferences
                    .decode(knowledge.originReferencesJson)
                    .single()
            assertEquals(id, origin.resourceId)
            assertEquals("User authored observations", origin.text)
            assertTrue(
                library.noteService.management
                    .export(knowledge)
                    .contains("User authored observations"),
            )
            val originalSources = store.noteSources(id).map { it.sourceId }
            val knowledgeSources = store.noteSources(knowledgeId).map { it.sourceId }
            assertEquals(originalSources, knowledgeSources)
            library.noteService.save(id, "Changed", "Later text", false)
            assertEquals(knowledge, store.note(knowledgeId))
            assertEquals("Edited conclusion", library.references.select("KNOWLEDGE", knowledgeId, "FULL").text)
        }

    @Test
    fun newEditorSnapshotHasNoDatabaseRowUntilExplicitSave() =
        fixture { library, store ->
            val note =
                com.helix.core.storage.knowledge.KnowledgeNote(
                    UUID.randomUUID().toString(),
                    "New note",
                    "",
                    1L,
                    1L,
                )
            assertEquals(null, store.note(note.id))
            library.noteService.saveNew(note, "Saved", "Synthetic content", false)
            assertEquals("Synthetic content", store.note(note.id)?.body)
            var refused = false
            try {
                library.noteService.saveNew(note, "Duplicate", "Cannot replace existing", true)
            } catch (_: IllegalStateException) {
                refused = true
            }
            assertTrue(refused)
            assertEquals("Synthetic content", store.note(note.id)?.body)
        }

    @Test
    fun deletingSavedAnswerNoteDetachesMarkerAndAllowsExplicitSaveAgain() =
        fixture { library, store ->
            val answer =
                com.helix.core.storage.knowledge.KnowledgeAnswer(
                    "deletion-answer",
                    "Question",
                    0,
                    "READY",
                    "[]",
                    "{}",
                    "Answer body",
                )
            store.saveAnswer(answer)
            val first = store.saveAnswerAsNote(answer.id, emptyList())
            assertEquals(first, store.saveAnswerAsNote(answer.id, emptyList()))
            library.noteService.management.remove(requireNotNull(store.note(first)))
            assertEquals("", store.answer(answer.id)?.savedNoteId)
            val second = store.saveAnswerAsNote(answer.id, emptyList())
            assertTrue(first != second)
            assertEquals("Answer body", store.note(second)?.body)
        }

    @Test
    fun deleteNoteRemovesIndexAndLinksButPreservesSources() =
        fixture { library, store ->
            val ids = collectSources(library)
            val id = createStoredTestNote(store, ids, "UniqueDeletionFixture")
            val note = requireNotNull(store.note(id))
            library.noteService.management.remove(note)
            assertEquals(null, store.note(id))
            assertTrue(store.noteSources(id).isEmpty())
            assertTrue(store.searchNotes("UniqueDeletionFixture").isEmpty())
            assertTrue(ids.all { store.get(it) != null })
        }

    @Test
    fun staleDeleteDoesNotDiscardNewEdits() =
        fixture { library, store ->
            val id = createStoredTestNote(store, collectSources(library), "Before")
            val old = requireNotNull(store.note(id))
            library.noteService.save(id, "After", "Keep edits", false)
            var refused = false
            try {
                library.noteService.management.remove(old)
            } catch (_: IllegalStateException) {
                refused = true
            }
            assertTrue(refused)
            assertEquals("Keep edits", store.note(id)?.body)
        }

    @Test
    fun explicitReferenceRefreshKeepsBodyAndMissingSourceLeavesEverythingUntouched() =
        fixture { library, store ->
            val ids = collectSources(library)
            val id = createStoredTestNote(store, ids, "References")
            library.noteService.save(id, "References", "User body", true)
            val frozen = store.noteSources(id)
            val source = requireNotNull(store.get(ids.first()))
            store.save(source.copy(extracted = "Updated evidence"))
            assertTrue(library.noteService.management.changed(frozen))
            library.noteService.management.refresh(requireNotNull(store.note(id)), frozen)
            val refreshed = requireNotNull(store.note(id))
            assertTrue(refreshed.updatedAt > 0)
            assertEquals("User body", refreshed.body)
            assertEquals("DRAFT", refreshed.state)
            assertTrue(store.noteSources(id).any { it.excerpt.contains("Updated evidence") })
            library.remove(ids.first())
            val before = store.noteSources(id)
            var refused = false
            try {
                library.noteService.management.refresh(refreshed, before)
            } catch (_: IllegalArgumentException) {
                refused = true
            }
            assertTrue(refused)
            assertEquals(before, store.noteSources(id))
            assertEquals(refreshed, store.note(id))
        }

    @Test
    fun manualAndManyToManyNotesKeepSourcesIndependent() =
        fixture { library, store ->
            val ids = collectSources(library)
            val a = createStoredTestNote(store, ids, "合成知识")
            val b = createStoredTestNote(store, listOf(ids.first()), "另一笔记")
            val manual = createStoredTestNote(store, emptyList(), "手写笔记")
            library.noteService.save(manual, "手写笔记", "Independent authored knowledge", true)
            assertEquals(2, store.noteSources(a).size)
            assertEquals(1, store.noteSources(b).size)
            assertTrue(store.noteSources(manual).isEmpty())
            assertEquals(2, library.items.first().size)
            assertEquals("REVIEWED", store.note(manual)?.state)
            assertEquals(
                manual,
                library.noteService
                    .search("authored")
                    .single()
                    .id,
            )
            assertEquals(
                a,
                library.noteService
                    .search("合成")
                    .single()
                    .id,
            )
        }

    @Test
    fun reExtractionDoesNotOverwriteReviewedKnowledgeOrCitationSnapshots() =
        fixture { library, store ->
            val ids = collectSources(library)
            val id = createStoredTestNote(store, ids, "Stable knowledge")
            library.noteService.save(id, "Stable knowledge", "Owner edited note", true)
            val sources = store.noteSources(id)
            val original = requireNotNull(store.get(ids.first()))
            store.save(original.copy(extracted = "Newly extracted page", summary = "New source note"))
            assertEquals("Owner edited note", store.note(id)?.body)
            assertEquals("REVIEWED", store.note(id)?.state)
            assertEquals(sources, store.noteSources(id))
        }

    @Test fun savedSuggestionsSurviveAndInterruptedWorkIsNotReplayed() =
        fixture { library, store ->
            val id = createStoredTestNote(store, collectSources(library), "Retained note")
            val original = requireNotNull(store.note(id))
            val interrupted =
                original.copy(
                    body = "Saved user body",
                    generatedDraft = "Retained suggestion",
                    generationState = "RUNNING",
                )
            store.saveNote(interrupted)
            val reopened = KnowledgeNoteService(store)
            try {
                reopened.recovery.join()
                val restored = requireNotNull(store.note(id))
                assertEquals("Saved user body", restored.body)
                assertEquals("Retained suggestion", restored.generatedDraft)
                assertEquals("INTERRUPTED", restored.generationState)
                assertEquals("NOTE", restored.kind)
            } finally {
                reopened.close()
            }
        }

    private suspend fun collectSources(service: KnowledgeService): List<String> {
        listOf("Synthetic Harbor budget: 137 tokens.", "Synthetic Harbor owner: TEAM_SYNTH_42; date unknown.")
            .forEach { text ->
                service
                    .capture(
                        Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, text),
                        "synthetic fixture",
                    ).join()
            }
        return service.items.first().map { it.id }
    }

    private fun fixture(block: suspend (KnowledgeService, KnowledgeStore) -> Unit) =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val name = "knowledge-notes-${UUID.randomUUID()}.db"
            val root = File(context.cacheDir, name)
            val store = KnowledgeStore(context, name)
            val service = KnowledgeService(context, store = store, root = root)
            service.recovery.join()
            try {
                block(service, store)
            } finally {
                service.close()
                context.deleteDatabase(name)
                root.deleteRecursively()
            }
        }
}
