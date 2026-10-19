package com.helix.app.knowledge

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.storage.knowledge.CollectedSource
import com.helix.core.storage.knowledge.KnowledgeAnswer
import com.helix.core.storage.knowledge.KnowledgeNoteSource
import com.helix.core.storage.knowledge.KnowledgeStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class KnowledgeQuestionUiDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun localSearchSelectionAndNoResultsNeverSendOrCreateANote() =
        fixture { service, store, _ ->
            compose.onNodeWithTag("knowledge-qa-question").performTextReplacement("Harbor")
            compose.onNodeWithTag("knowledge-qa-search").performClick()
            compose.waitUntil(5000) {
                compose
                    .onAllNodes(
                        androidx.compose.ui.test
                            .hasTestTag("knowledge-qa-select-source"),
                    ).fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithTag("knowledge-qa-select-source").performScrollTo().performClick()
            compose.onNodeWithTag("knowledge-qa-preview").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("knowledge-qa-question").performScrollTo().performTextReplacement("Zephyr")
            compose.onNodeWithTag("knowledge-qa-search").performClick()
            compose.waitUntil(5000) {
                compose
                    .onAllNodes(
                        androidx.compose.ui.test
                            .hasTestTag("knowledge-qa-select-source"),
                    ).fetchSemanticsNodes()
                    .isEmpty()
            }
            runBlocking {
                assertTrue(store.answers.first().isEmpty())
                assertTrue(store.notes.first().isEmpty())
            }
        }

    @Test fun generatedAnswerIsSeparateAndSaveOpensOnlyAnUnreviewedDraft() =
        fixture { _, store, opened ->
            val source = KnowledgeNoteSource("", "source", "Synthetic", "", 1, "Harbor budget 137", "hash", "READY")
            runBlocking {
                store.saveAnswer(
                    KnowledgeAnswer(
                        "answer",
                        "Harbor budget?",
                        2,
                        "READY",
                        KnowledgeQuestionService.encodeSources(listOf(source)),
                        "{}",
                        "137 [source:source]",
                    ),
                )
            }
            compose.waitUntil(5000) {
                compose
                    .onAllNodes(
                        androidx.compose.ui.test
                            .hasTestTag("knowledge-answer-save-answer"),
                    ).fetchSemanticsNodes()
                    .isNotEmpty()
            }
            assertTrue(runBlocking { store.notes.first().isEmpty() })
            compose.onNodeWithTag("knowledge-answer-save-answer").performScrollTo().performClick()
            compose.waitUntil(5000) { opened.get() != null }
            val note = runBlocking { requireNotNull(store.note(requireNotNull(opened.get()))) }
            assertEquals("DRAFT", note.state)
            assertEquals("137 [source:source]", note.body)
            assertEquals(1, runBlocking { store.noteSources(note.id).size })
        }

    private fun fixture(block: (KnowledgeQuestionService, KnowledgeStore, AtomicReference<String?>) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "knowledge-question-ui-${UUID.randomUUID()}.db"
        val store = KnowledgeStore(context, name)
        val service = KnowledgeQuestionService(store)
        val opened = AtomicReference<String?>()
        val visible = androidx.compose.runtime.mutableStateOf(true)
        runBlocking {
            service.recovery.join()
            val source = CollectedSource("source", "Synthetic", 1, "fixture", "", "Harbor budget 137", state = "READY")
            store.insert(source)
            store.save(source)
        }
        compose.setContent {
            if (visible.value) MaterialTheme { KnowledgeQuestionScreen(service, null, {}, { opened.set(it) }) }
        }
        try {
            block(service, store, opened)
        } finally {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            service.close()
            store.close()
            context.deleteDatabase(name)
        }
    }
}
