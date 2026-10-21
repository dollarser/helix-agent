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
            val id = library.noteService.create(ids, "UniqueDeletionFixture")
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
            val id = library.noteService.create(collectSources(library), "Before")
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
            val id = library.noteService.create(ids, "References")
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
            val a = library.noteService.create(ids, "合成知识")
            val b = library.noteService.create(listOf(ids.first()), "另一笔记")
            val manual = library.noteService.create(emptyList(), "手写笔记")
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
            val id = library.noteService.create(ids, "Stable knowledge")
            library.noteService.save(id, "Stable knowledge", "Owner edited note", true)
            val sources = store.noteSources(id)
            val original = requireNotNull(store.get(ids.first()))
            store.save(original.copy(extracted = "Newly extracted page", summary = "New source note"))
            assertEquals("Owner edited note", store.note(id)?.body)
            assertEquals("REVIEWED", store.note(id)?.state)
            assertEquals(sources, store.noteSources(id))
        }

    @Test
    fun generatedSuggestionRequiresSeparateReviewAndSave() =
        fixture { library, store ->
            val id = library.noteService.create(collectSources(library), "Synthesis")
            library.noteService.save(id, "Synthesis", "User draft survives", false)
            val request = prepared(library, id) { "Synthetic synthesis, needs review." }
            library.noteService.generate(request).join()
            val suggested = requireNotNull(store.note(id))
            assertEquals("DRAFT", suggested.state)
            assertEquals("READY", suggested.generationState)
            assertEquals("User draft survives", suggested.body)
            request.sources.forEach { assertTrue(suggested.generatedDraft.contains("[source:${it.sourceId}]")) }
            library.noteService.save(id, "Reviewed synthesis", suggested.generatedDraft + "\nUser correction", true)
            val saved = requireNotNull(store.note(id))
            assertEquals("REVIEWED", saved.state)
            assertTrue(saved.body.endsWith("User correction"))
            assertEquals("", saved.generatedDraft)
            assertEquals(request.sources, store.noteSources(id))
        }

    @Test
    fun failedGenerationRetainsUserDraftAndExplicitRetryWorks() =
        fixture { library, store ->
            val id = library.noteService.create(collectSources(library), "Failure")
            library.noteService.save(id, "Failure", "Do not lose edits", false)
            val failed = prepared(library, id) { error("synthetic provider failure") }
            library.noteService.generate(failed).join()
            assertEquals("FAILED", store.note(id)?.generationState)
            assertEquals("Do not lose edits", store.note(id)?.body)
            assertEquals("", store.note(id)?.generatedDraft)
            library.noteService.generate(prepared(library, id) { "Recovered suggestion" }).join()
            assertEquals("READY", store.note(id)?.generationState)
        }

    @Test
    fun cancellationPersistsInterruptionWithoutSavingGeneratedKnowledge() =
        fixture { library, store ->
            val id = library.noteService.create(collectSources(library), "Cancel")
            val entered = CompletableDeferred<Unit>()
            val job =
                library.noteService.generate(
                    prepared(library, id) {
                        entered.complete(Unit)
                        awaitCancellation()
                    },
                )
            entered.await()
            job.cancelAndJoin()
            assertEquals("INTERRUPTED", store.note(id)?.generationState)
            assertEquals("DRAFT", store.note(id)?.state)
            assertEquals("", store.note(id)?.generatedDraft)
        }

    @Test
    fun staleConfirmationDoesNotCallModelOrOverwriteEdits() =
        fixture { library, store ->
            val id = library.noteService.create(collectSources(library), "Identity")
            var called = false
            val request =
                prepared(library, id) {
                    called = true
                    "must not run"
                }
            library.noteService.save(id, "Changed topic", "Changed draft", false)
            library.noteService.generate(request).join()
            assertFalse(called)
            assertEquals("Changed draft", store.note(id)?.body)
            assertEquals(id, library.noteService.failure.value)
        }

    @Test
    fun modelStreamRejectsPartialToolCallsAndInventedCitations() =
        runBlocking {
            val good = flowOf(ModelEvent.TextDelta("Supported [source:real]"), ModelEvent.Completed("stop"))
            assertEquals("Supported [source:real]", KnowledgeAnalysis.collectAnswer(good, setOf("real")))
            val invalid =
                listOf(
                    flowOf(ModelEvent.TextDelta("partial")),
                    flowOf(ModelEvent.TextDelta("partial"), ModelEvent.Error(ModelErrorCode.PROTOCOL, false)),
                    flowOf(ModelEvent.TextDelta("limited"), ModelEvent.Completed("length")),
                    flowOf(ModelEvent.TextDelta("False [source:invented]"), ModelEvent.Completed("stop")),
                    flowOf(ModelEvent.ToolCallFinished(0), ModelEvent.Completed("tool_calls")),
                )
            invalid.forEach { events ->
                var rejected = false
                try {
                    KnowledgeAnalysis.collectAnswer(events, setOf("real"))
                } catch (
                    _: IllegalStateException,
                ) {
                    rejected = true
                }
                assertTrue("An incomplete, tool-bearing or falsely cited stream must fail", rejected)
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

    private suspend fun prepared(
        service: KnowledgeService,
        id: String,
        execute: suspend () -> String,
    ): PreparedKnowledgeAnalysis =
        PreparedKnowledgeAnalysis(
            requireNotNull(service.noteService.get(id)),
            service.noteService.sources(id),
            EgressDisclosure.EgressTarget("synthetic", "Synthetic model", null, "", ProviderResidence.ON_DEVICE_LOCAL),
            "synthetic-model",
            100,
            knowledgeHash("fixture"),
            execute,
        )

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
