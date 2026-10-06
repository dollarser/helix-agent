package com.helix.app.provider

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
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
import com.helix.app.ui.container
import com.helix.app.ui.deleteEditableProviders
import com.helix.app.ui.editableProviderTag
import com.helix.app.ui.navigateTo
import com.helix.app.ui.resetDeterministicUiState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real loopback discovery and the current selection dialog, not the retired inline chip/edit UI. */
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
        filterModels("fixture-model-b")
        composeRule.onNodeWithTag("provider-model-choice-fixture-model-b").assertIsOff().performClick()
        composeRule.onNodeWithTag("provider-model-choice-fixture-model-b").assertIsOn()
        composeRule.onNodeWithTag("provider-model-choice-fixture-model-a").assertDoesNotExist()
        composeRule.onNodeWithTag("provider-form-dialog").assertDoesNotExist()
        composeRule.onNodeWithTag("provider-models-close").performClick()
        awaitModelsClosed()
        assertEquals(before, row(id).modelSelection)

        openModels(id)
        filterModels("fixture-model-b")
        composeRule.onNodeWithTag("provider-model-choice-fixture-model-b").assertIsOff().performClick()
        composeRule.onNodeWithTag("provider-models-save").performClick()
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
        testConnection("passed")
        val before = row(id).modelSelection
        openModels(id)
        composeRule.onNodeWithTag("provider-models-refresh").performScrollTo().performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("provider-models-catalog-scope").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(before, row(id).modelSelection)
        assertEquals(null, row(id).backendModels)
        composeRule.onNodeWithTag("provider-model-list").performScrollToNode(hasTestTag("provider-model-add-entry"))
        composeRule.onNodeWithTag("provider-model-add-entry").performClick()
        composeRule.onNodeWithTag("provider-model-list").performScrollToNode(hasTestTag("provider-model-manual"))
        composeRule.onNodeWithTag("provider-model-manual").performTextInput("manual-fixture")
        androidx.test.espresso.Espresso
            .closeSoftKeyboard()
        composeRule.onNodeWithTag("provider-models-add").performScrollTo().performClick()
        composeRule.onNodeWithTag("provider-models-save").performClick()
        awaitModelsClosed()
        assertTrue("manual-fixture" in row(id).conversationModels)
        assertTrue(row(id).chatSelectable)
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
        composeRule.onNodeWithText("认证或访问权限被服务端拒绝", substring = true).performScrollTo().assertIsDisplayed()
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
            .onNodeWithTag("provider-model-list")
            .performScrollToNode(hasTestTag("provider-model-choice-fixture-model-299"))
        composeRule.onNodeWithTag("provider-model-choice-fixture-model-299").assertIsDisplayed()
        composeRule.onNodeWithTag("provider-model-list").performScrollToNode(hasTestTag("provider-model-search"))
        filterModels("fixture-model-299")
        composeRule.onNodeWithTag("provider-model-choice-fixture-model-299").assertIsOff().performClick()
        composeRule.onNodeWithTag("provider-model-choice-fixture-model-298").assertDoesNotExist()
        composeRule.onNodeWithTag("provider-models-save").performClick()
        awaitModelsClosed()
        assertEquals((before.models + "fixture-model-299").toSet(), row(id).conversationModels.toSet())
        assertEquals(300, row(id).backendModels?.size)
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
        composeRule.onNodeWithTag("provider-model-add-entry").performScrollTo().performClick()
        composeRule.onNodeWithTag("provider-form-model").performTextClearance()
        composeRule.onNodeWithTag("provider-form-model").performTextInput(model)
        if (key != null) {
            composeRule.onNodeWithTag("provider-form-key").performTextInput(key)
        }
        composeRule.onNodeWithTag("provider-cleartext-warning").performScrollTo().assertIsDisplayed()
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
            composeRule.onAllNodesWithTag("provider-model-list").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun filterModels(query: String) {
        composeRule.onNodeWithTag("provider-model-search").performTextReplacement(query)
        androidx.test.espresso.Espresso
            .closeSoftKeyboard()
        composeRule.onNodeWithTag("provider-model-list").performScrollToNode(hasTestTag("provider-model-choice-$query"))
    }

    private fun awaitModelsClosed() {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("provider-model-list").fetchSemanticsNodes().isEmpty()
        }
    }
}
