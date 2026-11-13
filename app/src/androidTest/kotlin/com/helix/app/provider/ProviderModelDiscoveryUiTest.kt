package com.helix.app.provider

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.MainActivity
import com.helix.app.R
import com.helix.app.ui.container
import com.helix.app.ui.deleteEditableProviders
import com.helix.app.ui.editableProviderTag
import com.helix.app.ui.editableProviderText
import com.helix.app.ui.navigateTo
import com.helix.app.ui.resetDeterministicUiState
import com.helix.core.model.ModelErrorCode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real loopback discovery through the single-page API service draft editor. */
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
    fun discoveredModelsAreExplicitChoicesAndCancelDoesNotSave() {
        val port = startServer(LoopbackModelServer.Mode.OPENAI_LISTED)
        val name = "Model Discovery ${System.currentTimeMillis()}"
        val id = createProvider(name, "http://127.0.0.1:$port/v1", "fixture-model-z")
        testConnection("passed")
        val before = row(id).modelSelection
        assertEquals(listOf("fixture-model-a", "fixture-model-b", "fixture-model-c"), row(id).backendModels)
        openModels(id)
        formNode("provider-model-choice-fixture-model-b").assertIsDisplayed()
        composeRule.onNodeWithTag("provider-model-search").assertDoesNotExist()
        composeRule.onNodeWithTag("provider-model-choice-fixture-model-b").assertIsOff().performClick()
        formNode("provider-model-choice-fixture-model-b").assertIsOn()
        composeRule.onNodeWithTag("provider-form-dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("provider-form-cancel").performClick()
        awaitModelsClosed()
        assertEquals(before, row(id).modelSelection)

        openModels(id)
        formNode("provider-model-choice-fixture-model-b").assertIsDisplayed()
        composeRule.onNodeWithTag("provider-model-search").assertDoesNotExist()
        composeRule.onNodeWithTag("provider-model-choice-fixture-model-b").assertIsOff().performClick()
        composeRule.onNodeWithTag("provider-form-save").performClick()
        awaitModelsClosed()
        assertEquals((before.models + "fixture-model-b").toSet(), row(id).conversationModels.toSet())
        assertEquals("fixture-model-z", row(id).model)
        assertTrue(row(id).chatSelectable)
        // Choosing a candidate is not a capability test for that model.
        assertFalse(row(id).modelVerifications.containsKey("fixture-model-b"))
        deleteProviderAndAwait(name)
    }

    @Test
    fun aBackendWithoutAListEndpointKeepsExplicitManualEntry() {
        val port = startServer(LoopbackModelServer.Mode.ANTHROPIC_UNSUPPORTED)
        val name = "No List ${System.currentTimeMillis()}"
        val id = createProvider(name, "http://127.0.0.1:$port/v1", "fixture-model-z", "anthropic", "fixture-key")
        testConnection("failed")
        assertTrue((row(id).status as ConnectionTestStatus.Failed).catalogUnsupported)
        assertFalse(row(id).chatSelectable)
        val before = row(id).modelSelection
        openModels(id)
        formNode("provider-discover-models").performClick()
        val unsupported =
            hasText(composeRule.activity.getString(R.string.provider_directory_unsupported)) and
                hasAnyAncestor(hasTestTag("provider-form-dialog"))
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(unsupported).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(unsupported).performScrollTo().assertIsDisplayed()
        assertEquals(before, row(id).modelSelection)
        assertEquals(null, row(id).backendModels)
        formNode("provider-model-add-entry").performClick()
        formNode("provider-model-manual").performTextInput("manual-fixture")
        androidx.test.espresso.Espresso
            .closeSoftKeyboard()
        formNode("provider-models-add").performClick()
        composeRule.onNodeWithTag("provider-form-save").performClick()
        awaitModelsClosed()
        assertTrue("manual-fixture" in row(id).conversationModels)
        assertFalse(row(id).chatSelectable)
        assertTrue((row(id).status as ConnectionTestStatus.Failed).catalogUnsupported)
        assertTrue(row(id).conversationModels.none(row(id)::modelSelectable))
        deleteProviderAndAwait(name)
    }

    @Test
    fun aCatalogAuthenticationFailureRevokesSelectionWithoutDroppingPreferences() {
        val port = startServer(LoopbackModelServer.Mode.OPENAI_PHASE2_AUTH)
        val name = "Phase Two Fail ${System.currentTimeMillis()}"
        val id = createProvider(name, "http://127.0.0.1:$port/v1", "fixture-model-z")
        val before = row(id).modelSelection

        // Connection checks use a single catalog fetch. A subsequent explicit check
        // reaches the fixture's 401 and must revoke selectability with a stable phase.
        composeRule.onNode(editableProviderTag("provider-test")).performScrollTo().performClick()
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodes(editableProviderTag("provider-status-passed")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(editableProviderTag("provider-test")).performScrollTo().performClick()
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodes(editableProviderTag("provider-status-failed")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(editableProviderTag("provider-status-failed")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("失败阶段：模型列表", substring = true).assertIsDisplayed()
        val failure = row(id).status as ConnectionTestStatus.Failed
        assertEquals(ModelErrorCode.AUTH, failure.code)
        assertFalse(failure.retryable)
        val authLabel = composeRule.activity.getString(ConnectionTestMapping.codeLabel(ModelErrorCode.AUTH))
        composeRule.onNode(editableProviderText(authLabel)).performScrollTo().assertIsDisplayed()
        assertFalse(row(id).chatSelectable)
        assertEquals(before, row(id).modelSelection)
        assertTrue(row(id).conversationModels.none(row(id)::modelSelectable))

        deleteProviderAndAwait(name)
    }

    @Test
    fun aLargeCatalogRemainsSearchableBeyondTheRetiredChipLimit() {
        val port = startServer(LoopbackModelServer.Mode.OPENAI_LARGE)
        val name = "Large List ${System.currentTimeMillis()}"
        val id = createProvider(name, "http://127.0.0.1:$port/v1", "fixture-model-z")
        testConnection("passed")
        assertEquals(300, row(id).backendModels?.size)
        val before = row(id).modelSelection
        openModels(id)
        // Lazy composition keeps all choices reachable without instantiating 300 row nodes.
        composeRule
            .onNodeWithTag("provider-form-scroll")
            .performScrollToNode(hasTestTag("provider-model-choice-fixture-model-299"))
        composeRule.onNodeWithTag("provider-model-choice-fixture-model-299").assertIsDisplayed()
        composeRule.onNodeWithTag("provider-form-scroll").performScrollToNode(hasTestTag("provider-model-search"))
        filterModels("fixture-model-299")
        composeRule.onNodeWithTag("provider-model-choice-fixture-model-299").assertIsOff().performClick()
        composeRule.onNodeWithTag("provider-model-choice-fixture-model-298").assertDoesNotExist()
        composeRule.onNodeWithTag("provider-form-save").performClick()
        awaitModelsClosed()
        assertEquals((before.models + "fixture-model-299").toSet(), row(id).conversationModels.toSet())
        assertEquals(300, row(id).backendModels?.size)
        deleteProviderAndAwait(name)
    }

    // --- helpers -------------------------------------------------------------

    /** The long catalog can leave this action off-screen; deletion then finishes off Compose. */
    private fun deleteProviderAndAwait(name: String) {
        composeRule
            .onNode(
                androidx.compose.ui.test.SemanticsMatcher("Provider overflow") {
                    val tag = androidx.compose.ui.semantics.SemanticsProperties.TestTag
                    it.config.contains(tag) && it.config[tag].startsWith("provider-overflow-")
                },
            ).performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("provider-delete").performClick()
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

    /** Creates a provider from the given template against the fixture endpoint (HTTP is warning-only). */
    private fun createProvider(
        name: String,
        endpoint: String,
        model: String,
        template: String = "generic-openai",
        key: String? = null,
    ): String {
        composeRule.navigateTo("models")
        composeRule.onNodeWithTag("provider-group-USER_CONFIGURED").performClick()
        composeRule.onNodeWithTag("provider-add").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("provider-template-$template").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("provider-form-dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("provider-form-name").performTextClearance()
        composeRule.onNodeWithTag("provider-form-name").performTextInput(name)
        composeRule.onNodeWithTag("provider-form-endpoint").performTextClearance()
        composeRule.onNodeWithTag("provider-form-endpoint").performTextInput(endpoint)
        if (key != null) {
            formNode("provider-form-key").performTextInput(key)
        }
        formNode("provider-model-manual").performTextReplacement(model)
        androidx.test.espresso.Espresso
            .closeSoftKeyboard()
        formNode("provider-cleartext-warning").assertIsDisplayed()
        composeRule.onNodeWithTag("provider-cleartext-confirm").assertDoesNotExist()
        composeRule.onNodeWithTag("provider-form-save").assertIsEnabled().performClick()
        // Compose idle does not wait for the Room/Keystore work on the IO dispatcher.
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("provider-form-dialog").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("provider-form-dialog").assertIsNotDisplayed()
        composeRule.onNodeWithText(name).performScrollTo().assertIsDisplayed()
        return composeRule
            .container()
            .providerService.rows.value
            .single { it.displayName == name }
            .id
    }

    private fun row(id: String) =
        composeRule
            .container()
            .providerService.rows.value
            .single { it.id == id }

    private fun testConnection(status: String) {
        composeRule.onNode(editableProviderTag("provider-test")).performScrollTo().performClick()
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodes(editableProviderTag("provider-status-$status")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openModels(id: String) {
        composeRule.onNodeWithTag("provider-manage-models-$id").performScrollTo().performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("provider-form-scroll").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun filterModels(query: String) {
        formNode("provider-model-search").performTextReplacement(query)
        androidx.test.espresso.Espresso
            .closeSoftKeyboard()
        composeRule
            .onNodeWithTag(
                "provider-form-scroll",
            ).performScrollToNode(hasTestTag("provider-model-choice-$query"))
    }

    private fun formNode(tag: String): androidx.compose.ui.test.SemanticsNodeInteraction {
        composeRule.onNodeWithTag("provider-form-scroll").performScrollToNode(hasTestTag(tag))
        return composeRule.onNodeWithTag(tag).performScrollTo()
    }

    private fun awaitModelsClosed() {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("provider-form-scroll").fetchSemanticsNodes().isEmpty()
        }
    }
}
