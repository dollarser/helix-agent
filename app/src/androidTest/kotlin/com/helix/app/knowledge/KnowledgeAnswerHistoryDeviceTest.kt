package com.helix.app.knowledge

import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.storage.knowledge.KnowledgeAnswer
import com.helix.core.storage.knowledge.KnowledgeNoteSource
import com.helix.core.storage.knowledge.KnowledgeStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** History retention replaces the retired parallel model-generation workflow. */
class KnowledgeAnswerHistoryDeviceTest {
    @Test fun interruptedHistoryRetainsSavedTextAndDoesNotCreateOrReplayAnything() =
        fixture { store ->
            val answer = KnowledgeAnswer("old", "Synthetic question", 1, "RUNNING", "[]", "{}", "Saved partial text")
            store.saveAnswer(answer)
            val history = KnowledgeAnswerHistory(store)
            try {
                history.recovery.join()
                assertEquals("INTERRUPTED", store.answer("old")?.state)
                assertEquals("Saved partial text", store.answer("old")?.body)
                assertTrue(store.notes.first().isEmpty())
                assertEquals(1, store.answers.first().size)
            } finally {
                history.close()
            }
        }

    @Test fun explicitSaveIsIdempotentAndPreservesFrozenSourcesWithoutMarkingKnowledge() =
        fixture { store ->
            val source =
                KnowledgeNoteSource(
                    "",
                    "source-a",
                    "Synthetic source",
                    "",
                    1,
                    "Frozen source body",
                    "hash",
                    "READY",
                )
            store.saveAnswer(
                KnowledgeAnswer(
                    "saved",
                    "Synthetic question",
                    1,
                    "READY",
                    encodeAnswerFixtureSources(listOf(source)),
                    "{}",
                    "Retained answer",
                ),
            )
            val history = KnowledgeAnswerHistory(store)
            try {
                val id = history.save("saved")
                assertEquals(id, history.save("saved"))
                val note = requireNotNull(store.note(id))
                assertEquals("Retained answer", note.body)
                assertEquals("NOTE", note.kind)
                assertEquals("DRAFT", note.state)
                assertEquals("Frozen source body", store.noteSources(id).single().excerpt)
                assertEquals(1, store.notes.first().size)
            } finally {
                history.close()
            }
        }

    @Test fun incompleteAnswerCannotBeSavedAsSuccessfulKnowledge() =
        fixture { store ->
            store.saveAnswer(KnowledgeAnswer("failed", "Question", 1, "FAILED", "[]", "{}", "Partial"))
            val history = KnowledgeAnswerHistory(store)
            try {
                var refused = false
                try {
                    history.save("failed")
                } catch (_: IllegalStateException) {
                    refused = true
                }
                assertTrue(refused)
                assertTrue(store.notes.first().isEmpty())
            } finally {
                history.close()
            }
        }

    private fun fixture(block: suspend (KnowledgeStore) -> Unit) =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val name = "knowledge-history-${UUID.randomUUID()}.db"
            val store = KnowledgeStore(context, name)
            try {
                block(store)
            } finally {
                clearKnowledgeDocuments(context, name)
            }
        }
}
