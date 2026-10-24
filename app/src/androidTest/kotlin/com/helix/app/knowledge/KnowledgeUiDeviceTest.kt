package com.helix.app.knowledge

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixUiDesign
import com.helix.app.R
import com.helix.app.ui.stableChatLayoutScreenshot
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
    private val restoration by lazy { StateRestorationTester(compose) }

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

    @Test
    fun markdownReadingAndEditingNeverSaveImplicitly() =
        fixture { service, _, _ ->
            val id =
                runBlocking {
                    service.noteService.create(emptyList(), "Markdown sample").also {
                        service.noteService.save(it, "Markdown sample", "# Heading\n\n**Saved fact**", true)
                    }
                }
            compose.onNodeWithTag("knowledge-tab-notes").performClick()
            compose.waitUntil(
                5000,
            ) { compose.onAllNodes(hasTestTag("knowledge-note-$id")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("knowledge-note-$id").performClick()
            scroll("knowledge-note-preview", "knowledge-note-editor")
            compose.onNodeWithTag("knowledge-note-preview").assertIsDisplayed()
            compose.onNodeWithTag("knowledge-note-body").assertDoesNotExist()
            scroll("knowledge-note-toggle-mode", "knowledge-note-editor")
            compose.onNodeWithTag("knowledge-note-toggle-mode").performClick()
            scroll("knowledge-note-body", "knowledge-note-editor")
            compose.onNodeWithTag("knowledge-note-body").performTextReplacement("# Unsaved edit")
            scroll("knowledge-note-toggle-mode", "knowledge-note-editor")
            compose.onNodeWithTag("knowledge-note-toggle-mode").performClick()
            assertEquals("# Heading\n\n**Saved fact**", runBlocking { service.noteService.get(id)?.body })
            restoration.emulateSavedInstanceStateRestore()
            scroll("knowledge-note-toggle-mode", "knowledge-note-editor")
            compose.onNodeWithTag("knowledge-note-toggle-mode").performClick()
            scroll("knowledge-note-body", "knowledge-note-editor")
            compose.onNodeWithTag("knowledge-note-body").assertTextContains("# Unsaved edit")
            assertEquals("REVIEWED", runBlocking { service.noteService.get(id)?.state })
        }

    @Test
    fun searchAndTabSurviveRecreationWithoutDeletingContent() =
        fixture { service, _, _ ->
            compose.onNodeWithTag("knowledge-tab-notes").performClick()
            compose.onNodeWithTag("knowledge-search").performTextReplacement("no-synthetic-match")
            compose.waitUntil(
                5000,
            ) { compose.onAllNodes(hasTestTag("knowledge-library-empty")).fetchSemanticsNodes().isNotEmpty() }
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithTag("knowledge-search").assertTextContains("no-synthetic-match")
            compose.onNodeWithTag("knowledge-notes-list").assertIsDisplayed()
            assertEquals(1, runBlocking { service.items.first().size })
        }

    @Test
    fun clearingSourceSelectionDoesNotOpenOrDeleteIt() =
        fixture { service, _, draft ->
            val source = runBlocking { service.items.first().single() }
            compose.onNodeWithTag("knowledge-select-${source.id}").performClick()
            compose.onNodeWithTag("knowledge-clear-selection").performClick()
            compose.onNodeWithTag("knowledge-synthesize").assertDoesNotExist()
            compose.onNodeWithTag("knowledge-detail").assertDoesNotExist()
            assertEquals(
                source.id,
                runBlocking {
                    service.items
                        .first()
                        .single()
                        .id
                },
            )
            assertNull(draft.get())
        }

    @Test
    fun sourceDeletionRequiresConfirmationAndCancelKeepsOriginal() =
        fixture { service, store, _ ->
            val source = runBlocking { service.items.first().single() }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val deletion = context.getString(R.string.knowledge_delete_sources)
            compose.onNodeWithTag("knowledge-item-${source.id}").performClick()
            compose.onNodeWithTag("knowledge-detail").performScrollToNode(hasText(deletion))
            compose.onNodeWithText(deletion).performClick()
            compose.onNodeWithText(context.getString(R.string.knowledge_delete_sources_warning)).assertIsDisplayed()
            capture("source-delete-confirmation")
            compose.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
            assertEquals(source.rawText, runBlocking { store.get(source.id)?.rawText })
            assertNull(runBlocking { store.get(source.id) }?.let { if (it.state == "DELETING") it else null })
        }

    @Test
    fun libraryAndMarkdownViewsKeepTheirActionsReachable() =
        fixture(extraSources = 5) { service, _, _ ->
            compose.onNodeWithTag("knowledge-search").assertIsDisplayed()
            compose.onNodeWithTag("knowledge-library-settings").assertIsDisplayed()
            capture("sources")
            val id =
                runBlocking {
                    service.noteService.create(emptyList(), "合成 Markdown 笔记 · 阅读与编辑").also {
                        service.noteService.save(
                            it,
                            "合成 Markdown 笔记 · 阅读与编辑",
                            "# 项目记录\n\n**这是合成资料，不含个人数据。**\n\n" +
                                "- 保留原始资料\n- 核对后保存知识\n\n> 引用与正文保持独立。\n\n" +
                                "```kotlin\nval synthetic = true\n```",
                            true,
                        )
                    }
                }
            compose.onNodeWithTag("knowledge-tab-notes").performClick()
            compose.waitUntil(
                5000,
            ) { compose.onAllNodes(hasTestTag("knowledge-note-$id")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("knowledge-new-note").assertIsDisplayed()
            capture("notes")
            compose.onNodeWithTag("knowledge-note-$id").performClick()
            scroll("knowledge-note-preview", "knowledge-note-editor")
            compose.onNodeWithTag("knowledge-note-preview").assertIsDisplayed()
            capture("reading")
            scroll("knowledge-note-toggle-mode", "knowledge-note-editor")
            compose.onNodeWithTag("knowledge-note-toggle-mode").performClick()
            scroll("knowledge-note-body", "knowledge-note-editor")
            compose.onNodeWithTag("knowledge-note-body").assertIsDisplayed()
            capture("editing")
            scroll("knowledge-note-management", "knowledge-note-editor")
            compose.onNodeWithTag("knowledge-note-management").performClick()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val delete = context.getString(R.string.knowledge_delete_note)
            compose.onNodeWithTag("knowledge-note-editor").performScrollToNode(hasText(delete))
            compose.onNodeWithText(delete).performClick()
            compose.onNodeWithText(context.getString(R.string.knowledge_delete_note_warning)).assertIsDisplayed()
            capture("note-delete-confirmation")
            compose.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
            assertEquals("REVIEWED", runBlocking { service.noteService.get(id)?.state })
            val export = context.getString(R.string.knowledge_export_note)
            compose.onNodeWithTag("knowledge-note-editor").performScrollToNode(hasText(export))
            compose.onNodeWithText(export).assertIsDisplayed()
        }

    private fun capture(name: String) {
        val profile = InstrumentationRegistry.getArguments().getString("knowledgeUiCapture") ?: return
        require(profile.matches(Regex("[a-z0-9-]{1,30}")))
        compose.waitForIdle()
        val bitmap = stableChatLayoutScreenshot()
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "knowledge-ui-captures")
        check(root.isDirectory || root.mkdirs())
        try {
            File(root, "$profile-$name.png").outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun scroll(
        tag: String,
        parent: String = "knowledge-detail",
    ) {
        compose.onNodeWithTag(parent).performScrollToNode(hasTestTag(tag))
    }

    private fun addSources(
        service: KnowledgeService,
        extraSources: Int,
    ) {
        repeat(extraSources) { index ->
            runBlocking {
                service
                    .capture(
                        Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, "合成资料 ${index + 1} · 用于检查较长标题和列表换行\n这是布局测试内容。"),
                        "模拟器合成资料",
                    ).join()
            }
        }
    }

    private fun fixture(
        extraSources: Int = 0,
        block: (KnowledgeService, KnowledgeStore, AtomicReference<String?>) -> Unit,
    ) {
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
        addSources(service, extraSources)
        service.openRequests.value = null
        try {
            restoration.setContent {
                if (visible.value) {
                    MaterialTheme {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .safeDrawingPadding()
                                .wrapContentWidth(Alignment.CenterHorizontally)
                                .widthIn(max = HelixUiDesign.contentWidth),
                        ) {
                            KnowledgeScreen(service, draft::set)
                        }
                    }
                }
            }
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
