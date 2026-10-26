package com.helix.app.knowledge

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.storage.knowledge.KnowledgeNote
import com.helix.core.storage.knowledge.KnowledgeStore
import com.helix.core.storage.repository.KnowledgeReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Real selector and Room content; no model, sending or tool permission is granted by selection. */
class KnowledgeReferenceUiDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val restoration by lazy { StateRestorationTester(compose) }

    @Test fun cancellationDoesNotApplySelection() =
        fixture { _, applied ->
            compose.onNodeWithTag("knowledge-reference-select-synthetic").performClick()
            compose.onNodeWithTag("knowledge-reference-picker-close").performClick()
            assertNull(applied.get())
        }

    @Test fun checkedSelectionRestoresAndExplicitAddReturnsOnlySelectedContent() =
        fixture { store, applied ->
            compose.onNodeWithTag("knowledge-reference-select-synthetic").performClick()
            selected("synthetic")
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithTag("knowledge-reference-apply").assertIsDisplayed().performClick()
            compose.waitUntil(5000) { applied.get() != null }
            val item = requireNotNull(applied.get()).single()
            assertEquals("synthetic", item.resourceId)
            assertEquals("FULL", item.range)
            assertEquals("Synthetic selected text", item.text)
            assertEquals("NOTE", runBlocking { store.note("synthetic")?.kind })
        }

    @Test fun selectionsSurviveChangingTheSearchFilter() =
        fixture { _, applied ->
            compose.onNodeWithTag("knowledge-reference-select-synthetic").performClick()
            selected("synthetic")
            compose.onNodeWithTag("knowledge-reference-search").performTextReplacement("Unselected")
            compose.waitUntil(5000) {
                compose.onAllNodes(hasTestTag("knowledge-reference-select-other")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("knowledge-reference-select-other").performClick()
            selected("other")
            compose.onNodeWithTag("knowledge-reference-apply").performClick()
            compose.waitUntil(5000) { applied.get() != null }
            assertEquals(setOf("synthetic", "other"), requireNotNull(applied.get()).map { it.resourceId }.toSet())
        }

    @Test fun changedSelectionRequiresExplicitVersionChoice() =
        fixture { store, applied ->
            compose.onNodeWithTag("knowledge-reference-select-synthetic").performClick()
            selected("synthetic")
            runBlocking {
                val note = requireNotNull(store.note("synthetic"))
                store.saveNote(note.copy(body = "Changed after checking", updatedAt = 2))
            }
            compose.onNodeWithTag("knowledge-reference-apply").performClick()
            compose.waitUntil(5000) {
                compose.onAllNodes(hasTestTag("knowledge-reference-error")).fetchSemanticsNodes().isNotEmpty()
            }
            assertNull(applied.get())
            compose.onNodeWithTag("knowledge-reference-preview-synthetic").performScrollTo().performClick()
            compose.waitUntil(5000) {
                compose.onAllNodes(hasTestTag("knowledge-reference-keep-version")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("knowledge-reference-keep-version").performScrollTo().performClick()
            compose.onNodeWithTag("knowledge-reference-preview-close").performClick()
            compose.onNodeWithTag("knowledge-reference-apply").performClick()
            compose.waitUntil(5000) { applied.get() != null }
            val ref = requireNotNull(applied.get()).single()
            assertEquals("Synthetic selected text", ref.text)
            assertEquals(true, ref.keepVersion)
        }

    private fun selected(id: String) {
        compose.waitUntil(5000) {
            compose
                .onAllNodes(hasTestTag("knowledge-reference-select-$id"))
                .fetchSemanticsNodes()
                .firstOrNull()
                ?.config
                ?.get(androidx.compose.ui.semantics.SemanticsProperties.ToggleableState) ==
                androidx.compose.ui.state.ToggleableState.On
        }
    }

    private fun fixture(block: (KnowledgeStore, AtomicReference<List<KnowledgeReference>?>) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "knowledge-picker-${UUID.randomUUID()}.db"
        val store = KnowledgeStore(context, name)
        val applied = AtomicReference<List<KnowledgeReference>?>(null)
        val visible = mutableStateOf(true)
        runBlocking {
            store.saveNote(KnowledgeNote("synthetic", "Synthetic note", "Synthetic selected text", 1, 1))
            store.saveNote(KnowledgeNote("other", "Unselected note", "Never included", 1, 1))
        }
        try {
            restoration.setContent {
                com.helix.app.HelixTheme {
                    if (visible.value) {
                        KnowledgeReferencePicker(
                            KnowledgeReferenceService(store),
                            emptyList(),
                            onApply = {
                                applied.set(it)
                                visible.value = false
                            },
                            onDismiss = { visible.value = false },
                        )
                    }
                }
            }
            compose.waitUntil(5000) {
                compose
                    .onAllNodes(
                        hasTestTag("knowledge-reference-select-synthetic"),
                    ).fetchSemanticsNodes()
                    .isNotEmpty()
            }
            block(store, applied)
        } finally {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()

            clearKnowledgeDocuments(context, name)
        }
    }
}
