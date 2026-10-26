package com.helix.app.knowledge

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
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

    @Test fun prototypeLibraryUsesOnePrimaryActionAndSecondaryManagement() =
        fixture(extraSources = 5) { _, _, _ ->
            compose.onNodeWithTag("knowledge-search").assertIsDisplayed()
            compose.onNodeWithTag("knowledge-new-note").assertIsDisplayed()
            compose.onNodeWithTag("knowledge-library-settings").assertDoesNotExist()
            compose.onNodeWithTag("knowledge-import").assertDoesNotExist()
            capture("sources")
            compose.onNodeWithTag("knowledge-library-more").performClick()
            compose.onNodeWithTag("knowledge-import").assertIsDisplayed()
            compose.onNodeWithTag("knowledge-library-settings").assertIsDisplayed()
        }

    @Test fun saveNoteIsExplicitAndDoesNotBecomeReviewedKnowledge() =
        fixture { service, _, _ ->
            compose.onNodeWithTag("knowledge-new-note").performClick()
            compose.onNodeWithTag("knowledge-note-title").performTextReplacement("Synthetic note")
            scroll("knowledge-note-body", "knowledge-note-editor")
            compose.onNodeWithTag("knowledge-note-body").performTextReplacement("# Original observation")
            assertTrue(
                runBlocking {
                    service.noteService.notes
                        .first()
                        .isEmpty()
                },
            )
            capture("editing")
            compose.onNodeWithTag("knowledge-note-save").performClick()
            compose.waitUntil(5000) {
                runBlocking {
                    service.noteService.notes
                        .first()
                        .size == 1
                }
            }
            val note =
                runBlocking {
                    service.noteService.notes
                        .first()
                        .single()
                }
            assertEquals("NOTE", note.kind)
            assertEquals("DRAFT", note.state)
            compose.onNodeWithTag("knowledge-note-preview").assertIsDisplayed()
            compose.onNodeWithTag("knowledge-note-save").assertDoesNotExist()
            capture("reading")
            compose.onNodeWithTag("knowledge-note-back").performClick()
            capture("notes")
        }

    @Test fun unsavedEditorRestoresAndDiscardDoesNotCreateANote() =
        fixture { service, _, _ ->
            compose.onNodeWithTag("knowledge-new-note").performClick()
            scroll("knowledge-note-body", "knowledge-note-editor")
            compose.onNodeWithTag("knowledge-note-body").performTextReplacement("Unsaved synthetic content")
            restoration.emulateSavedInstanceStateRestore()
            compose.waitUntil(
                5000,
            ) { compose.onAllNodes(hasTestTag("knowledge-note-body")).fetchSemanticsNodes().isNotEmpty() }
            scroll("knowledge-note-body", "knowledge-note-editor")
            compose.onNodeWithTag("knowledge-note-body").assertTextContains("Unsaved synthetic content")
            compose.onNodeWithTag("knowledge-note-back").performClick()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            compose.onNodeWithText(context.getString(R.string.common_discard_edit)).performClick()
            assertTrue(
                runBlocking {
                    service.noteService.notes
                        .first()
                        .isEmpty()
                },
            )
            compose.onNodeWithTag("knowledge-search").assertIsDisplayed()
        }

    @Test fun readingIsBodyFirstAndEditingDoesNotSaveUntilConfirmed() =
        fixture { service, _, _ ->
            val id =
                runBlocking {
                    service.noteService.createEmptyTestNote("Synthetic Markdown").also {
                        service.noteService.save(
                            requireNotNull(service.noteService.get(it)),
                            "Synthetic Markdown",
                            "# Heading\n\n**Saved fact**",
                            true,
                        )
                    }
                }
            compose.onNodeWithTag("knowledge-tab-notes").performClick()
            compose.waitUntil(
                5000,
            ) { compose.onAllNodes(hasTestTag("knowledge-note-$id")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("knowledge-note-$id").performClick()
            compose.onNodeWithTag("knowledge-note-preview").assertIsDisplayed()
            compose.onNodeWithTag("knowledge-note-save").assertDoesNotExist()
            compose.onNodeWithTag("knowledge-note-model").assertDoesNotExist()
            capture("reading")
            compose.onNodeWithTag("knowledge-note-toggle-mode").performClick()
            scroll("knowledge-note-body", "knowledge-note-editor")
            compose.onNodeWithTag("knowledge-note-body").performTextReplacement("# Unsaved edit")
            assertEquals("# Heading\n\n**Saved fact**", runBlocking { service.noteService.get(id)?.body })
            capture("editing")
            compose.onNodeWithTag("knowledge-note-save").performClick()
            compose.waitUntil(5000) { runBlocking { service.noteService.get(id)?.body == "# Unsaved edit" } }
            assertEquals("DRAFT", runBlocking { service.noteService.get(id)?.state })
            compose.onNodeWithTag("knowledge-note-management").performClick()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            compose.onNodeWithText(context.getString(R.string.knowledge_export_note)).assertIsDisplayed()
        }

    @Test fun sourceReaderShowsBodyAndReferencesWithoutManagementForms() =
        fixture { service, store, draft ->
            val source = runBlocking { service.items.first().single() }
            compose.onNodeWithTag("knowledge-item-${source.id}").performClick()
            compose.onNodeWithTag("knowledge-source-body").assertIsDisplayed()
            compose.onNodeWithTag("knowledge-summary").assertDoesNotExist()
            compose.onNodeWithTag("knowledge-raw").assertDoesNotExist()
            capture("source-reading")
            compose.onNodeWithTag("knowledge-reference").performClick()
            compose.waitUntil(5000) { draft.get() != null }
            val selected =
                com.helix.core.storage.repository.KnowledgeReferences
                    .decode(
                        requireNotNull(draft.get()),
                    ).single()
            assertEquals(source.id, selected.resourceId)
            assertEquals(source.rawText, selected.text)
            assertTrue(runBlocking { store.notes.first().isEmpty() })
        }

    @Test fun sourceCandidateCancelHasNoSideEffectAndSavePreservesOriginal() =
        fixture { service, store, _ ->
            val source = runBlocking { service.items.first().single() }
            compose.onNodeWithTag("knowledge-item-${source.id}").performClick()
            compose.onNodeWithTag("knowledge-distill").performClick()
            compose.onNodeWithTag("knowledge-candidate-close").performClick()
            assertTrue(runBlocking { store.notes.first().isEmpty() })
            compose.onNodeWithTag("knowledge-distill").performClick()
            compose.onNodeWithTag("knowledge-candidate-title").performTextReplacement("Explicit conclusion")
            compose.onNodeWithTag("knowledge-candidate-body").performTextReplacement("Edited synthetic conclusion")
            compose.onNodeWithTag("knowledge-candidate-save").assertIsDisplayed().performClick()
            compose.waitUntil(5000) { runBlocking { store.notes.first().size == 1 } }
            val note = runBlocking { store.notes.first().single() }
            assertEquals("KNOWLEDGE", note.kind)
            assertEquals("DRAFT", note.state)
            assertEquals("Edited synthetic conclusion", note.body)
            assertEquals(source, runBlocking { store.get(source.id) })
            assertEquals(listOf(source.id), runBlocking { store.noteSources(note.id).map { it.sourceId } })
            capture("knowledge-explicit-candidate")
        }

    @Test fun noteCandidatePreservesOriginalAndRequiresExplicitSave() =
        fixture { service, store, _ ->
            val id =
                runBlocking {
                    service.noteService.createEmptyTestNote("Synthetic observations").also {
                        service.noteService.save(
                            requireNotNull(service.noteService.get(it)),
                            "Synthetic observations",
                            "# Observation",
                            false,
                        )
                    }
                }
            compose.onNodeWithTag("knowledge-tab-notes").performClick()
            compose.waitUntil(
                5000,
            ) { compose.onAllNodes(hasTestTag("knowledge-note-$id")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("knowledge-note-$id").performClick()
            compose.onNodeWithTag("knowledge-create-knowledge").performClick()
            compose.onNodeWithTag("knowledge-candidate-close").performClick()
            assertEquals(1, runBlocking { store.notes.first().size })
            compose.onNodeWithTag("knowledge-create-knowledge").performClick()
            compose.onNodeWithTag("knowledge-candidate-save").assertIsDisplayed().performClick()
            compose.waitUntil(5000) { runBlocking { store.notes.first().size == 2 } }
            assertEquals("# Observation", runBlocking { store.note(id)?.body })
            assertEquals(
                "DRAFT",
                runBlocking {
                    store.notes
                        .first()
                        .single { it.kind == "KNOWLEDGE" }
                        .state
                },
            )
        }

    @Test fun searchAndCategoryRestoreAndEmptyStateCanClearQuery() =
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
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            compose.onNodeWithText(context.getString(R.string.knowledge_clear_search)).performClick()
            compose.onNodeWithText(context.getString(R.string.knowledge_clear_search)).assertDoesNotExist()
        }

    @Test fun sourceDeleteIsSecondaryAndCancelPreservesOriginal() =
        fixture { service, store, _ ->
            val source = runBlocking { service.items.first().single() }
            compose.onNodeWithTag("knowledge-item-${source.id}").performClick()
            compose.onNodeWithTag("knowledge-note-management").performClick()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            compose
                .onNodeWithTag(
                    "knowledge-source-info-list",
                ).performScrollToNode(hasText(context.getString(R.string.knowledge_delete_sources)))
            compose.onNodeWithText(context.getString(R.string.knowledge_delete_sources)).performClick()
            capture("source-delete-confirmation")
            compose.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
            assertEquals(source, runBlocking { store.get(source.id) })
        }

    @Test fun importCancellationDoesNotCreateContent() =
        fixture { service, _, _ ->
            compose.onNodeWithTag("knowledge-library-more").performClick()
            compose.onNodeWithTag("knowledge-import").performClick()
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            compose.waitUntil(5000) {
                automation.rootInActiveWindow
                    ?.packageName
                    ?.toString()
                    ?.endsWith("documentsui") == true
            }
            val backAction = android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            assertTrue(automation.performGlobalAction(backAction))
            compose.waitUntil(5000) {
                compose.onAllNodes(hasTestTag("knowledge-search")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("knowledge-search").assertIsDisplayed()
            assertEquals(1, runBlocking { service.items.first().size })
            assertTrue(
                runBlocking {
                    service.noteService.notes
                        .first()
                        .isEmpty()
                },
            )
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
        val service = KnowledgeService(context, store = store)
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
                    com.helix.app.HelixTheme {
                        androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .safeDrawingPadding()
                                    .wrapContentWidth(Alignment.CenterHorizontally)
                                    .widthIn(max = HelixUiDesign.contentWidth),
                            ) {
                                KnowledgeScreen(service, { refs ->
                                    captureReferences(draft, refs)
                                })
                            }
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
            compose.onNodeWithTag("knowledge-tab-sources").performClick()
            block(service, store, draft)
        } finally {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            service.close()
            clearKnowledgeDocuments(context, name)
        }
    }
}

private fun captureReferences(
    draft: AtomicReference<String?>,
    references: List<com.helix.core.storage.repository.KnowledgeReference>,
): Boolean {
    draft.set(
        com.helix.core.storage.repository.KnowledgeReferences
            .encode(references),
    )
    return true
}
