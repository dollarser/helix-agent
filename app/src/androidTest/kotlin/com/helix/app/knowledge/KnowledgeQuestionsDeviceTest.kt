package com.helix.app.knowledge

import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.chat.EgressDisclosure
import com.helix.core.model.ModelEvent
import com.helix.core.model.ProviderResidence
import com.helix.core.storage.knowledge.CollectedSource
import com.helix.core.storage.knowledge.KnowledgeAnswer
import com.helix.core.storage.knowledge.KnowledgeNote
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
import java.util.UUID

class KnowledgeQuestionsDeviceTest {
    @Test fun offlineRetrievalFindsChineseAndMultipleSources() =
        fixture { service, store ->
            add(store, "a", "Harbor budget 137. 项目预算137。")
            add(store, "b", "Harbor owner TEAM_SYNTH_42. 项目负责人。")
            add(store, "c", "Unrelated source")
            assertEquals(setOf("a", "b"), service.search("What is Harbor budget and owner?").map { it.id }.toSet())
            assertTrue(service.search("预算和负责人分别是什么").map { it.id }.containsAll(listOf("a", "b")))
            assertTrue(store.answers.first().isEmpty())
            assertTrue(store.notes.first().isEmpty())
        }

    @Test fun noResultsOrPunctuationDoesNotInventAnAnswer() =
        fixture { service, store ->
            add(store, "a", "Harbor budget 137")
            assertTrue(service.search("Zephyr").isEmpty())
            assertTrue(service.search("!?").isEmpty())
            assertTrue(store.answers.first().isEmpty())
        }

    @Test fun answerSaveRetainsFrozenCitationsAfterSourceDeletion() =
        fixture { service, store ->
            add(store, "a", "Harbor budget 137")
            add(store, "b", "Harbor owner TEAM_SYNTH_42")
            val request =
                request(service, listOf("a", "b")) {
                    "Budget 137 [source:a]; owner TEAM_SYNTH_42 [source:b]."
                }
            service.ask(request).join()
            assertEquals("READY", store.answer(request.note.id)?.state)
            assertTrue(store.notes.first().isEmpty())
            store.remove("a")
            val id = service.save(request.note.id)
            assertEquals(id, service.save(request.note.id))
            assertEquals(1, store.notes.first().size)
            assertEquals("DRAFT", store.note(id)?.state)
            assertEquals(setOf("a", "b"), store.noteSources(id).map { it.sourceId }.toSet())
            assertTrue(
                store
                    .noteSources(id)
                    .first { it.sourceId == "a" }
                    .excerpt
                    .contains("137"),
            )
            assertEquals(request.execute(), store.note(id)?.body)
        }

    @Test fun failedForgedOrUncitedAnswerIsNotSavedAsKnowledge() =
        fixture { service, store ->
            add(store, "a", "Harbor budget 137")
            val executors: List<suspend () -> String> =
                listOf(
                    { error("synthetic") },
                    { "False [source:forged]" },
                    { "No citations" },
                )
            executors.forEach { execute ->
                val request = request(service, listOf("a"), execute)
                service.ask(request).join()
                assertEquals("FAILED", store.answer(request.note.id)?.state)
                assertEquals("", store.answer(request.note.id)?.body)
                assertTrue(runCatching { service.save(request.note.id) }.isFailure)
            }
            assertTrue(store.notes.first().isEmpty())
        }

    @Test fun cancellationIsDurableAndDoesNotRetry() =
        fixture { service, store ->
            add(store, "a", "Harbor budget 137")
            val entered = CompletableDeferred<Unit>()
            val request =
                request(service, listOf("a")) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            val job = service.ask(request)
            entered.await()
            service.cancel(request.note.id)
            job.join()
            assertEquals("INTERRUPTED", store.answer(request.note.id)?.state)
            assertEquals("", store.answer(request.note.id)?.body)
            assertTrue(store.notes.first().isEmpty())
        }

    @Test fun repeatedConfirmationSharesOneExecution() =
        fixture { service, store ->
            add(store, "a", "Harbor budget 137")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var calls = 0
            val request =
                request(service, listOf("a")) {
                    calls++
                    entered.complete(Unit)
                    release.await()
                    "137 [source:a]"
                }
            val first = service.ask(request)
            entered.await()
            assertTrue(first === service.ask(request))
            release.complete(Unit)
            first.join()
            service.ask(request).join()
            assertEquals(1, calls)
            org.junit.Assert.assertNull(service.failure.value)
            assertEquals("READY", store.answer(request.note.id)?.state)
            assertEquals("137 [source:a]", store.answer(request.note.id)?.body)
        }

    @Test fun staleOrDeletedSourceInvalidatesConfirmationBeforeModelExecution() =
        fixture { service, store ->
            add(store, "a", "Harbor budget 137")
            var called = false
            val request =
                request(service, listOf("a")) {
                    called = true
                    "137 [source:a]"
                }
            store.save(requireNotNull(store.get("a")).copy(rawText = "changed"))
            service.ask(request).join()
            assertFalse(called)
            assertEquals(request.note.id, service.failure.value)
            assertTrue(store.answers.first().isEmpty())
            assertTrue(runCatching { service.evidence("Harbor", listOf("missing")) }.isFailure)
        }

    @Test fun interruptedRecoveryKeepsReadyAnswersAndDoesNotCallAModel() =
        fixture { service, store ->
            val sources = "[]"
            store.saveAnswer(KnowledgeAnswer("running", "Q", 1, "RUNNING", sources, "{}"))
            store.saveAnswer(KnowledgeAnswer("ready", "Q", 2, "READY", sources, "{}", "saved"))
            val recovered = KnowledgeQuestionService(store)
            try {
                recovered.recovery.join()
                assertEquals("INTERRUPTED", store.answer("running")?.state)
                assertEquals("saved", store.answer("ready")?.body)
            } finally {
                recovered.close()
            }
        }

    @Test fun questionAnalysisRejectsMissingCitationIncompleteAndToolStreams() =
        runBlocking {
            val invalid =
                listOf(
                    flowOf(ModelEvent.TextDelta("No citation"), ModelEvent.Completed("stop")),
                    flowOf(ModelEvent.TextDelta("137 [source:a]")),
                    flowOf(ModelEvent.TextDelta("137 [source:a]"), ModelEvent.Completed("length")),
                    flowOf(ModelEvent.ToolCallFinished(0), ModelEvent.Completed("tool_calls")),
                )
            invalid.forEach { events ->
                val result = runCatching { KnowledgeAnalysis.collectAnswer(events, setOf("a"), true) }
                assertTrue(result.isFailure)
            }
            val complete = flowOf(ModelEvent.TextDelta("137 [source:a]"), ModelEvent.Completed("stop"))
            assertEquals("137 [source:a]", KnowledgeAnalysis.collectAnswer(complete, setOf("a"), true))
        }

    @Test fun evidenceLimitsAndSourceCompletenessAreRetained() =
        fixture { service, store ->
            add(store, "a", "Intro ".repeat(3000) + "Harbor budget 137", "INCOMPLETE")
            val evidence = service.evidence("budget", listOf("a")).single()
            assertTrue(evidence.excerpt.contains("137"))
            assertTrue(evidence.completeness.startsWith("INCOMPLETE:"))
            assertTrue(evidence.excerpt.length <= 24_200)
            assertTrue(runCatching { service.evidence("Harbor", List(9) { "a" }) }.isFailure)
            assertTrue(runCatching { service.evidence("Harbor", emptyList()) }.isFailure)
        }

    private suspend fun add(
        store: KnowledgeStore,
        id: String,
        body: String,
        state: String = "READY",
    ) {
        val source = CollectedSource(id, "Synthetic $id", 1, "synthetic fixture", "", body, state = state)
        store.insert(source)
        store.save(source)
    }

    private suspend fun request(
        service: KnowledgeQuestionService,
        ids: List<String>,
        execute: suspend () -> String,
    ): PreparedKnowledgeAnalysis {
        val question = "Harbor budget and owner?"
        val note = KnowledgeNote(UUID.randomUUID().toString(), question, question, 1, 1)
        val target =
            EgressDisclosure.EgressTarget(
                "synthetic",
                "Synthetic model",
                null,
                "",
                ProviderResidence.ON_DEVICE_LOCAL,
            )
        return PreparedKnowledgeAnalysis(
            note,
            service.evidence(question, ids),
            target,
            "synthetic-model",
            100,
            knowledgeHash("fixture"),
            execute,
        )
    }

    private fun fixture(block: suspend (KnowledgeQuestionService, KnowledgeStore) -> Unit) =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val name = "knowledge-questions-${UUID.randomUUID()}.db"
            val store = KnowledgeStore(context, name)
            val service = KnowledgeQuestionService(store)
            service.recovery.join()
            try {
                block(service, store)
            } finally {
                service.close()
                store.close()
                context.deleteDatabase(name)
            }
        }
}
