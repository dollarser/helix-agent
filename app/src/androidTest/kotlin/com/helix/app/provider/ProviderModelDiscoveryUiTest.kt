package com.helix.app.provider

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.MainActivity
import com.helix.app.ui.container
import com.helix.app.ui.deleteEditableProviders
import com.helix.app.ui.editableProviderTag
import com.helix.app.ui.navigateTo
import com.helix.app.ui.resetDeterministicUiState
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * HXA-059 device suite: the provider model auto-discovery full chain against an
 * in-APK loopback fixture server ([LoopbackModelServer] — real OkHttp wire, real
 * protocol adapters, deterministic offline model replies):
 *
 * 1. create provider (cleartext http, explicit host:port confirmation) → save →
 *    connection test PASSES with a model list → the row shows 「后端可用模型 (N)」
 *    with filter + chips → selecting a chip OPENS the edit form with the model
 *    field PREFILLED (never auto-saved);
 * 2. a backend WITHOUT a model-list endpoint (Anthropic protocol: phase 2 =
 *    Unsupported) → the test still passes and the row shows the explicit
 *    manual-entry hint (no section);
 * 3. phase 2 FAILED (the models endpoint rejects after phase 1 passed) → the
 *    row shows the stable phase/code error and NO model section;
 * 4. a large backend list (300 ids) → the section caps at 200 chips with the
 *    「共 N 个，显示前 200」hint, and the filter still narrows below the cap.
 *
 * Note on case 2: an OpenAI-compatible backend CANNOT produce phase-2
 * Unsupported over HTTP — its phase-1 check IS the model-list call, so a 404
 * fails at phase 1. The protocol without a list endpoint is Anthropic
 * (`modelsPath() == null`), which is what this case drives.
 *
 * The model ids are opaque fixture strings; chips are tagged by display index
 * (never by the id) and matched by text.
 */
@RunWith(AndroidJUnit4::class)
class ProviderModelDiscoveryUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private var server: LoopbackModelServer? = null

    @Before
    fun setUp() {
        composeRule.resetDeterministicUiState()
        deleteEditableProviders(composeRule.container())
    }

    @After
    fun tearDown() {
        server?.close()
        server = null
    }

    @Test
    fun passedTestSurfacesBackendModelsAndChipPrefillsTheEditForm() {
        val port = startServer(LoopbackModelServer.Mode.OPENAI_LISTED)
        val name = "Model Discovery ${System.currentTimeMillis()}"
        createProvider(name, "http://127.0.0.1:$port/v1", "fixture-model-z")

        // --- the connection test PASSES and carries the 3-model list out ---
        composeRule.onNode(editableProviderTag("provider-test")).performScrollTo().performClick()
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodes(editableProviderTag("provider-status-passed")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(editableProviderTag("provider-status-passed")).performScrollTo().assertIsDisplayed()

        // --- the section shows the list with a filter ---
        composeRule.onNode(editableProviderTag("provider-models-section")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("后端可用模型 (3)").assertIsDisplayed()
        composeRule.onNodeWithText("fixture-model-a").assertExists()
        composeRule.onNodeWithText("fixture-model-b").assertExists()
        composeRule.onNodeWithText("fixture-model-c").assertExists()
        composeRule.onAllNodes(editableProviderTag("provider-models-unsupported")).fetchSemanticsNodes().isEmpty()

        // --- the filter narrows the displayed chips (index-based tags) ---
        composeRule.onNode(editableProviderTag("provider-models-filter")).performScrollTo().performTextInput("b")
        composeRule.onNode(editableProviderTag("provider-model-chip-0")).assertExists()
        assertTrue(chipTextOf("provider-model-chip-0") == "fixture-model-b")
        assertTrue(
            "filter must narrow to a single chip",
            composeRule.onAllNodes(editableProviderTag("provider-model-chip-1")).fetchSemanticsNodes().isEmpty(),
        )

        // --- selecting the chip OPENS the edit form (never auto-saved) ---
        composeRule.onNode(editableProviderTag("provider-models-filter")).performScrollTo().performTextClearance()
        composeRule.onNode(editableProviderTag("provider-model-chip-1")).performScrollTo().performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("provider-form-dialog").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("provider-form-dialog").assertIsDisplayed()
        // …and cancelling it changes NOTHING: the persisted row still shows the old model.
        composeRule.onNodeWithTag("provider-form-cancel").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("provider-form-dialog").assertIsNotDisplayed()
        composeRule.onNodeWithText("模型：fixture-model-z", substring = true).assertIsDisplayed()

        // --- the prefill is verified end to end: select → SAVE → the persisted row model ---
        // (the form's OutlinedTextField merged semantics carry only the label, never the typed
        // value, on this Compose version — the persisted value is the authoritative read).
        composeRule.onNode(editableProviderTag("provider-model-chip-1")).performScrollTo().performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("provider-form-dialog").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("provider-cleartext-confirm").performClick()
        composeRule.onNodeWithTag("provider-form-save").performClick()
        // The edit save does more Room/Keystore work than create (overwrite + status clear +
        // binding prune + refresh) — wait for the close explicitly instead of relying on idle.
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("provider-form-dialog").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText("模型：fixture-model-b", substring = true).assertIsDisplayed()

        // --- cleanup ---
        deleteProviderAndAwait(name)
    }

    @Test
    fun aBackendWithoutAListEndpointShowsTheManualEntryHint() {
        val port = startServer(LoopbackModelServer.Mode.ANTHROPIC_UNSUPPORTED)
        val name = "No List ${System.currentTimeMillis()}"
        createProvider(
            name,
            "http://127.0.0.1:$port/v1",
            "fixture-model-z",
            template = "anthropic",
            key = "fixture-key",
        )

        // The Anthropic backend has no model list: phase 1 validates by stream,
        // phase 2 is Unsupported (no HTTP call) and the probe still passes.
        composeRule.onNode(editableProviderTag("provider-test")).performScrollTo().performClick()
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodes(editableProviderTag("provider-status-passed")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(editableProviderTag("provider-status-passed")).performScrollTo().assertIsDisplayed()
        composeRule.onNode(editableProviderTag("provider-models-unsupported")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("后端未提供模型列表，请手动输入").assertIsDisplayed()
        composeRule.onAllNodes(editableProviderTag("provider-models-section")).fetchSemanticsNodes().isEmpty()

        deleteProviderAndAwait(name)
    }

    @Test
    fun aPhaseTwoFailureShowsTheStableErrorWithoutAModelSection() {
        val port = startServer(LoopbackModelServer.Mode.OPENAI_PHASE2_AUTH)
        val name = "Phase Two Fail ${System.currentTimeMillis()}"
        createProvider(name, "http://127.0.0.1:$port/v1", "fixture-model-z")

        // Phase 1 (the first models call) passes; phase 2 (the second call)
        // gets a 401 → the probe stops at phase 2 with the safe AUTH label.
        composeRule.onNode(editableProviderTag("provider-test")).performScrollTo().performClick()
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodes(editableProviderTag("provider-status-failed")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(editableProviderTag("provider-status-failed")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("失败阶段：模型列表", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("认证失败（key 缺失或无效）", substring = true).assertIsDisplayed()
        composeRule.onAllNodes(editableProviderTag("provider-models-section")).fetchSemanticsNodes().isEmpty()
        composeRule.onAllNodes(editableProviderTag("provider-models-unsupported")).fetchSemanticsNodes().isEmpty()

        deleteProviderAndAwait(name)
    }

    @Test
    fun aLargeBackendListIsDisplayCappedWithAHintAndStillFilterable() {
        val port = startServer(LoopbackModelServer.Mode.OPENAI_LARGE)
        val name = "Large List ${System.currentTimeMillis()}"
        createProvider(name, "http://127.0.0.1:$port/v1", "fixture-model-z")

        composeRule.onNode(editableProviderTag("provider-test")).performScrollTo().performClick()
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodes(editableProviderTag("provider-status-passed")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(editableProviderTag("provider-models-section")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("后端可用模型 (300)").assertIsDisplayed()

        // Display cap: 200 chips (index 0..199), then the truncation hint.
        composeRule.onNode(editableProviderTag("provider-model-chip-199")).assertExists()
        assertTrue(
            "the display cap is 200 chips",
            composeRule.onAllNodes(editableProviderTag("provider-model-chip-200")).fetchSemanticsNodes().isEmpty(),
        )
        // assertExists, not assertIsDisplayed: after 200 chips the hint sits below the fold
        // of the scrollable row list (off-screen nodes still exist in the semantics tree).
        composeRule.onNodeWithText("共 300 个，显示前 200").assertExists()

        // The filter narrows below the cap and the hint goes away.
        // ids are zero-padded (%03d), so "fixture-model-299" is the UNIQUE match — "fixture-model-29"
        // would match eleven ids (290-299) and leave multiple chips.
        composeRule
            .onNode(
                editableProviderTag("provider-models-filter"),
            ).performScrollTo()
            .performTextInput("fixture-model-299")
        assertTrue(chipTextOf("provider-model-chip-0") == "fixture-model-299")
        assertTrue(
            "the filter must narrow to a single chip",
            composeRule.onAllNodes(editableProviderTag("provider-model-chip-1")).fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(
            "below the cap the hint must disappear",
            composeRule.onAllNodesWithText("共 300 个，显示前 200").fetchSemanticsNodes().isEmpty(),
        )

        deleteProviderAndAwait(name)
    }

    // --- helpers -------------------------------------------------------------

    /** The long catalog can leave this action off-screen; deletion then finishes off Compose. */
    private fun deleteProviderAndAwait(name: String) {
        composeRule.onNode(editableProviderTag("provider-delete")).performScrollTo().performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(name).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText(name).assertIsNotDisplayed()
    }

    private fun startServer(mode: LoopbackModelServer.Mode): Int {
        val s = LoopbackModelServer(mode)
        s.start()
        server = s
        return s.port
    }

    /** Creates a provider from the given template against the fixture endpoint (cleartext confirmed). */
    private fun createProvider(
        name: String,
        endpoint: String,
        model: String,
        template: String = "ollama",
        key: String? = null,
    ) {
        composeRule.navigateTo("settings")
        composeRule.onNodeWithTag("provider-add").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("provider-template-$template").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("provider-form-dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("provider-form-name").performTextClearance()
        composeRule.onNodeWithTag("provider-form-name").performTextInput(name)
        composeRule.onNodeWithTag("provider-form-endpoint").performTextClearance()
        composeRule.onNodeWithTag("provider-form-endpoint").performTextInput(endpoint)
        composeRule.onNodeWithTag("provider-form-model").performTextClearance()
        composeRule.onNodeWithTag("provider-form-model").performTextInput(model)
        if (key != null) {
            composeRule.onNodeWithTag("provider-form-key").performTextInput(key)
        }
        // The cleartext http endpoint requires the explicit per-host:port risk
        // confirmation (ProviderFlowTest precedent; tag from the form dialog).
        composeRule.onNodeWithTag("provider-cleartext-confirm").performClick()
        composeRule.onNodeWithTag("provider-form-save").performClick()
        // Compose idle does not wait for the Room/Keystore work on the IO dispatcher.
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("provider-form-dialog").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("provider-form-dialog").assertIsNotDisplayed()
        composeRule.onNodeWithText(name).performScrollTo().assertIsDisplayed()
    }

    /** The display text of a chip (matched by text — the id never rides in the tag). */
    private fun chipTextOf(tag: String): String {
        val texts = composeRule.onNode(editableProviderTag(tag)).fetchSemanticsNode().config[SemanticsProperties.Text]
        return (texts as? List<*>)?.firstOrNull()?.toString() ?: error("chip $tag has no text")
    }
}
