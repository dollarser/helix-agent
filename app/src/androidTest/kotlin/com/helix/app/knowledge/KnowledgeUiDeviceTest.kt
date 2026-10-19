package com.helix.app.knowledge

import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.storage.knowledge.KnowledgeStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Replaces chat-prefill share tests with the collection-first product contract. No model is called. */
class KnowledgeUiDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun searchSelectAndReferenceOnlyChosenMaterial() =
        fixture { service, store, draft ->
            val item = runBlocking { service.items.first().single() }
            compose.onNodeWithTag("knowledge-search").performTextReplacement("知识")
            compose.waitUntil(5000) {
                compose
                    .onAllNodes(
                        androidx.compose.ui.test
                            .hasTestTag("knowledge-item-${item.id}"),
                    ).fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithTag("knowledge-item-${item.id}").performClick()
            scroll("knowledge-raw")
            compose.onNodeWithTag("knowledge-raw").assertTextEquals("Synthetic 知识 collection")
            assertNull(draft.get())
            scroll("knowledge-reference")
            compose.onNodeWithTag("knowledge-reference").performClick()
            compose.waitUntil(5000) { draft.get() != null }
            assertTrue(requireNotNull(draft.get()).contains("source:${item.id}"))
            assertEquals("Synthetic 知识 collection", runBlocking { store.get(item.id)?.rawText })
        }

    @Test
    fun analysisCreatesReviewedDraftWithoutSending() =
        fixture { service, _, draft ->
            val item = runBlocking { service.items.first().single() }
            compose.onNodeWithTag("knowledge-item-${item.id}").performClick()
            scroll("knowledge-analyze")
            compose.onNodeWithTag("knowledge-analyze").performClick()
            compose.waitUntil(5000) { draft.get() != null }
            assertTrue(requireNotNull(draft.get()).contains("source:${item.id}"))
            assertTrue(requireNotNull(draft.get()).contains("Untrusted reference material"))
            assertTrue(
                runBlocking {
                    service.noteService.notes
                        .first()
                        .isEmpty()
                },
            )
        }

    @Test
    fun saveSummaryDoesNotReplaceOriginalOrExtraction() =
        fixture { service, store, _ ->
            val item = runBlocking { service.items.first().single() }
            compose.onNodeWithTag("knowledge-item-${item.id}").performClick()
            scroll("knowledge-summary")
            compose.onNodeWithTag("knowledge-summary").performTextReplacement("Reviewed summary")
            scroll("knowledge-save-summary")
            compose.onNodeWithTag("knowledge-save-summary").performClick()
            compose.waitUntil(5000) {
                val summary = runBlocking { store.get(item.id)?.summary }
                summary == "Reviewed summary"
            }
            val saved = runBlocking { requireNotNull(store.get(item.id)) }
            assertEquals(item.rawText, saved.rawText)
            assertEquals(item.extracted, saved.extracted)
        }

    @Test
    fun distillSourceCreatesUnreviewedLinkedNote() =
        fixture { service, _, _ ->
            val source = runBlocking { service.items.first().single() }
            compose.onNodeWithTag("knowledge-item-${source.id}").performClick()
            scroll("knowledge-distill")
            compose.onNodeWithTag("knowledge-distill").performClick()
            compose.waitUntil(5000) {
                compose.onAllNodes(hasTestTag("knowledge-note-editor")).fetchSemanticsNodes().isNotEmpty()
            }
            val note =
                runBlocking {
                    service.noteService.notes
                        .first { it.isNotEmpty() }
                        .single()
                }
            assertEquals("DRAFT", note.state)
            assertEquals("IDLE", note.generationState)
            assertEquals(
                source.id,
                runBlocking {
                    service.noteService
                        .sources(note.id)
                        .single()
                        .sourceId
                },
            )
        }

    @Test
    fun manualKnowledgeCanBeEditedAndSavedWithoutSources() =
        fixture { service, _, _ ->
            compose.onNodeWithTag("knowledge-tab-notes").performClick()
            compose.onNodeWithTag("knowledge-new-note").performClick()
            compose.waitUntil(5000) {
                compose.onAllNodes(hasTestTag("knowledge-note-editor")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("knowledge-note-title").performTextReplacement("Synthetic manual knowledge")
            scroll("knowledge-note-body", "knowledge-note-editor")
            compose.onNodeWithTag("knowledge-note-body").performTextReplacement("Owner authored fact")
            scroll("knowledge-note-save", "knowledge-note-editor")
            compose.onNodeWithTag("knowledge-note-save").performClick()
            compose.waitUntil(5000) {
                runBlocking {
                    service.noteService.notes
                        .first()
                        .any { it.state == "REVIEWED" }
                }
            }
            val note =
                runBlocking {
                    service.noteService.notes
                        .first()
                        .single()
                }
            assertEquals("Owner authored fact", note.body)
            assertTrue(runBlocking { service.noteService.sources(note.id).isEmpty() })
        }

    @Test
    fun multiSourceSynthesisKeepsBothOriginals() =
        fixture { service, _, _ ->
            runBlocking {
                service
                    .capture(
                        Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, "Second synthetic fragment"),
                        "fixture",
                    ).join()
            }
            val sources = runBlocking { service.items.first { it.size == 2 } }
            compose.waitUntil(5000) {
                compose.onAllNodes(hasTestTag("knowledge-back")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("knowledge-back").performClick()
            sources.forEach { source ->
                val selection = hasTestTag("knowledge-select-${source.id}")
                compose.onNodeWithTag("knowledge-list").performScrollToNode(selection)
                compose.onNodeWithTag("knowledge-select-${source.id}").performClick()
            }
            compose.onNodeWithTag("knowledge-synthesize").performClick()
            compose.waitUntil(5000) {
                runBlocking {
                    service.noteService.notes
                        .first()
                        .isNotEmpty()
                }
            }
            val note =
                runBlocking {
                    service.noteService.notes
                        .first()
                        .single()
                }
            val linked =
                runBlocking {
                    service.noteService
                        .sources(note.id)
                        .map { it.sourceId }
                        .toSet()
                }
            assertEquals(sources.map { it.id }.toSet(), linked)
            assertEquals(2, runBlocking { service.items.first().size })
            assertEquals("DRAFT", note.state)
        }

    private fun scroll(
        tag: String,
        parent: String = "knowledge-detail",
    ) {
        compose.onNodeWithTag(parent).performScrollToNode(hasTestTag(tag))
    }

    private fun fixture(block: (KnowledgeService, KnowledgeStore, AtomicReference<String?>) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "knowledge-ui-${UUID.randomUUID()}.db"
        val store = KnowledgeStore(context, name)
        val root = File(context.cacheDir, name)
        val service = KnowledgeService(context, store = store, root = root)
        val draft = AtomicReference<String?>(null)
        val visible = androidx.compose.runtime.mutableStateOf(true)
        runBlocking {
            service
                .capture(
                    Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, "Synthetic 知识 collection"),
                    "synthetic source",
                ).join()
        }
        service.openRequests.value = null
        try {
            compose.setContent { if (visible.value) MaterialTheme { KnowledgeScreen(service, draft::set) } }
            compose.waitUntil(5000) {
                compose
                    .onAllNodes(
                        androidx.compose.ui.test
                            .hasTestTag("knowledge-search"),
                    ).fetchSemanticsNodes()
                    .isNotEmpty()
            }
            block(service, store, draft)
        } finally {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            service.close()
            context.deleteDatabase(name)
            root.deleteRecursively()
        }
    }
}
