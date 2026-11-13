package com.helix.app.provider.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import com.helix.app.provider.ProviderModelsIntegrationDeviceTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Exercise the production manager, shared create/edit editor and actual service persistence. */
class ProviderUnifiedSettingsDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun editSavesConnectionAndMultipleModelsTogetherThenCloses() =
        runBlocking {
            ProviderModelsIntegrationDeviceTest.Fixture().use { fixture ->
                fixture.service.saveSelectedModels("p", listOf("a"))
                showManager(fixture)
                openEditor()
                replace("provider-form-name", "Edited fixture")
                addModel("b", "Second model")
                addModel("new", "Third model")
                assertEquals("Fixture", fixture.service.storedConfig("p").displayName)
                assertEquals(
                    listOf("a"),
                    fixture.service.rows.value
                        .single()
                        .modelSelection.models,
                )
                click("provider-form-save")
                awaitClosed()
                assertEquals("Edited fixture", fixture.service.storedConfig("p").displayName)
                val saved =
                    fixture.service.rows.value
                        .single()
                        .modelSelection
                assertEquals(listOf("a", "b", "new"), saved.models)
                assertEquals("Second model", saved.displayNames["b"])
                assertEquals("Third model", saved.displayNames["new"])
                openEditor()
                scrolled("provider-model-choice-b").assertExists()
                scrolled("provider-model-choice-new").assertExists()
                assertEquals(0, fixture.generations)
            }
        }

    @Test fun createUsesSameAdderAndPersistsMultipleModels() =
        runBlocking {
            ProviderModelsIntegrationDeviceTest.Fixture().use { fixture ->
                showManager(fixture)
                click("provider-add")
                click("provider-template-generic-openai")
                replace("provider-form-name", "Created fixture")
                replace("provider-form-endpoint", "https://created.test/v1")
                addModel("heading", "First model")
                click("provider-model-add-entry")
                replace("provider-model-manual", "input")
                replace("provider-model-display-name", "Second model")
                assertEquals(1, fixture.service.rows.value.size)
                click("provider-form-save")
                awaitClosed()
                val created =
                    fixture.service.rows.value
                        .single { it.id != "p" }
                assertEquals(listOf("heading", "input"), created.modelSelection.models)
                assertEquals("First model", created.modelSelection.displayNames["heading"])
                assertEquals("Second model", created.modelSelection.displayNames["input"])
                assertEquals(0, fixture.generations)
            }
        }

    @Test fun cancelDiscardsConnectionAndModelDrafts() =
        runBlocking {
            ProviderModelsIntegrationDeviceTest.Fixture().use { fixture ->
                fixture.service.saveSelectedModels("p", listOf("a"))
                val before = fixture.service.storedConfig("p")
                val selection =
                    fixture.service.rows.value
                        .single()
                        .modelSelection
                showManager(fixture)
                openEditor()
                replace("provider-form-name", "Discard me")
                replace("provider-form-endpoint", "https://discarded.test/v1")
                addModel("discarded", "Discarded model")
                click("provider-form-cancel")
                awaitClosed()
                assertEquals(before, fixture.service.storedConfig("p"))
                assertEquals(
                    selection,
                    fixture.service.rows.value
                        .single()
                        .modelSelection,
                )
                openEditor()
                compose.onNodeWithTag("provider-form-name").assertTextContains("Fixture")
                compose.onNodeWithTag("provider-model-choice-discarded").assertDoesNotExist()
                compose.onNodeWithTag("provider-settings-connection").assertDoesNotExist()
                compose.onNodeWithTag("provider-settings-models").assertDoesNotExist()
                assertEquals(0, fixture.generations)
            }
        }

    @Test fun discoveryUsesUnsavedEndpointKeyAndHeadersWithoutPersisting() =
        runBlocking {
            ProviderModelsIntegrationDeviceTest.Fixture(draftCatalog = true).use { fixture ->
                fixture.service.saveSelectedModels("p", listOf("a"))
                val before = fixture.service.storedConfig("p")
                val selection =
                    fixture.service.rows.value
                        .single()
                        .modelSelection
                showManager(fixture)
                openEditor()
                replace("provider-form-endpoint", "https://draft.test/v1")
                replace("provider-form-key", "synthetic-draft-key")
                click("provider-form-advanced")
                replace("provider-form-header-name", "X-Request-Source")
                replace("provider-form-header-value", "synthetic-draft")
                click("provider-model-add-entry")
                click("provider-discover-models")
                compose.waitUntil(5000) { fixture.catalogRequests.isNotEmpty() }
                compose.waitForIdle()
                val request = fixture.catalogRequests.single()
                assertEquals("https://draft.test/v1/models", request.url)
                assertEquals("GET", request.method)
                assertEquals(
                    "Bearer synthetic-draft-key",
                    request.headers.entries
                        .single {
                            it.key.equals("Authorization", true)
                        }.value,
                )
                assertEquals(
                    "synthetic-draft",
                    request.headers.entries
                        .single { it.key.equals("X-Request-Source", true) }
                        .value,
                )
                assertEquals(before, fixture.service.storedConfig("p"))
                assertEquals(
                    selection,
                    fixture.service.rows.value
                        .single()
                        .modelSelection,
                )
                assertEquals(1, fixture.service.rows.value.size)
                assertEquals(0, fixture.generations)
                click("provider-form-cancel")
                awaitClosed()
                assertEquals(before, fixture.service.storedConfig("p"))
            }
        }

    @Test fun dirtyConnectionBlocksOldConnectionTestsButModelOnlyEditsDoNot() =
        runBlocking {
            ProviderModelsIntegrationDeviceTest.Fixture().use { fixture ->
                fixture.service.saveSelectedModels("p", listOf("a"))
                assertTrue(
                    fixture.service.runConnectionTest("p", "a", verifyGeneration = true) is
                        com.helix.provider.api.ProbeOutcome.Ok,
                )
                fixture.generations = 0
                fixture.requests.clear()
                fixture.probed.clear()
                showManager(fixture)
                openEditor()
                addModel("b", "Draft model")
                click("provider-model-details-a")
                scrolled("provider-model-test-a").assertIsEnabled()
                scrolled("provider-model-probe-a").assertIsEnabled()
                replace("provider-form-endpoint", "https://unsaved.test/v1")
                scrolled("provider-model-test-a").assertIsNotEnabled()
                scrolled("provider-model-probe-a").assertIsNotEnabled()
                assertEquals(0, fixture.generations)
                assertTrue(fixture.probed.isEmpty())
                replace("provider-form-endpoint", "https://example.test/v1")
                replace("provider-form-key", "synthetic-changed-key")
                assertTestsBlocked()
                replace("provider-form-key", "")
                click("provider-form-advanced")
                replace("provider-form-header-name", "X-Request-Source")
                replace("provider-form-header-value", "changed")
                assertTestsBlocked()
                replace("provider-form-header-name", "")
                replace("provider-form-header-value", "")
                click("provider-form-protocol")
                click("provider-protocol-OPENAI_RESPONSES")
                assertTestsBlocked()
                click("provider-form-protocol")
                click("provider-protocol-OPENAI_CHAT_COMPLETIONS")
                assertEquals(0, fixture.generations)
                assertTrue(fixture.probed.isEmpty())
                scrolled("provider-model-test-a").assertIsEnabled().performClick()
                compose.waitUntil(5000) { fixture.generations > 0 }
                assertEquals(
                    listOf("a"),
                    fixture.service.rows.value
                        .single()
                        .modelSelection.models,
                )
                assertEquals("a", fixture.requests.single().model)
            }
        }

    @Test fun manualVisionDeclarationLivesInBaseModelDetailsAndTakesEffectImmediately() =
        runBlocking {
            ProviderModelsIntegrationDeviceTest.Fixture().use { fixture ->
                fixture.service.saveSelectedModels("p", listOf("a"))
                fixture.service.declareVisionCapability("p", false)
                showManager(fixture)
                compose.onNodeWithTag("provider-vision-declare").assertDoesNotExist()
                openEditor()
                compose.onNodeWithTag("provider-vision-declare").assertDoesNotExist()
                addModel("b", "Unsaved model")
                click("provider-model-details-b")
                compose.onNodeWithTag("provider-vision-declare").assertDoesNotExist()
                click("provider-model-details-a")
                scrolled("provider-model-immediate-setting").assertExists()
                assertEquals(
                    false,
                    fixture.service.rows.value
                        .single()
                        .capabilitiesForModel("a")
                        ?.vision,
                )
                assertEquals(0, fixture.generations)
                assertTrue(fixture.probed.isEmpty())
                click("provider-vision-declare")
                compose.waitUntil(
                    5000,
                ) {
                    fixture.service.rows.value
                        .single()
                        .capabilitiesForModel("a")
                        ?.vision == true
                }
                click("provider-form-cancel")
                awaitClosed()
                assertEquals(
                    listOf("a"),
                    fixture.service.rows.value
                        .single()
                        .modelSelection.models,
                )
                assertEquals(
                    true,
                    fixture.service.rows.value
                        .single()
                        .capabilitiesForModel("a")
                        ?.vision,
                )
                compose.onNodeWithTag("provider-vision-declare").assertDoesNotExist()
                assertEquals(0, fixture.generations)
                assertTrue(fixture.probed.isEmpty())
            }
        }

    @Test fun contextSaveIsExplicitlyIndependentOfCancellingServiceDraft() =
        runBlocking {
            ProviderModelsIntegrationDeviceTest.Fixture().use { fixture ->
                fixture.service.saveSelectedModels("p", listOf("a"))
                val before =
                    fixture.service.rows.value
                        .single()
                        .modelSelection
                val initialContext = fixture.service.contextSettings("p", "a")
                val threshold = if (initialContext.triggerPercent == 65) 70 else 65
                showManager(fixture)
                openEditor()
                addModel("b", "Unsaved second model")
                click("provider-model-details-a")
                click("provider-model-context-a")
                compose.onNodeWithTag("provider-context-independent-save").assertExists()
                compose.waitUntil(5000) {
                    compose
                        .onAllNodesWithTag("provider-context-threshold")
                        .fetchSemanticsNodes()
                        .singleOrNull()
                        ?.config
                        ?.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) == false
                }
                compose
                    .onNodeWithTag("provider-context-threshold")
                    .performScrollTo()
                    .performTextReplacement(threshold.toString())
                compose.onNodeWithTag("provider-context-save").performClick()
                compose.waitUntil(5000) {
                    compose.onAllNodesWithTag("provider-context-save").fetchSemanticsNodes().isEmpty()
                }
                assertEquals(threshold, fixture.service.contextSettings("p", "a").triggerPercent)
                click("provider-form-cancel")
                awaitClosed()
                assertEquals(
                    before,
                    fixture.service.rows.value
                        .single()
                        .modelSelection,
                )
                assertEquals(threshold, fixture.service.contextSettings("p", "a").triggerPercent)
                assertEquals(0, fixture.generations)
            }
        }

    @Test fun failedSaveRetainsBothDraftsAndDeletedServiceCanBeCancelled() =
        runBlocking {
            ProviderModelsIntegrationDeviceTest.Fixture().use { fixture ->
                fixture.service.saveSelectedModels("p", listOf("a"))
                showManager(fixture)
                openEditor()
                replace("provider-form-name", "Retained draft")
                addModel("retained", "Retained model")
                fixture.service.delete("p")
                compose.waitForIdle()
                click("provider-form-save")
                compose.waitUntil(
                    5000,
                ) { compose.onAllNodesWithTag("provider-form-error").fetchSemanticsNodes().isNotEmpty() }
                scrolled("provider-form-name").assertTextContains("Retained draft")
                scrolled("provider-model-choice-retained").assertExists()
                assertTrue(
                    fixture.service.rows.value
                        .isEmpty(),
                )
                click("provider-form-cancel")
                awaitClosed()
            }
        }

    private fun assertTestsBlocked() {
        scrolled("provider-model-test-a").assertIsNotEnabled()
        scrolled("provider-model-probe-a").assertIsNotEnabled()
    }

    private fun showManager(fixture: ProviderModelsIntegrationDeviceTest.Fixture) {
        fixture.service.refresh()
        compose.waitUntil(5000) {
            fixture.service.rows.value
                .any { it.id == "p" }
        }
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) { ProviderManager(fixture.service) }
            }
        }
    }

    private fun openEditor() {
        click("provider-manage-models-p")
        compose.waitUntil(5000) { compose.onAllNodesWithTag("provider-form-name").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun addModel(
        id: String,
        displayName: String,
    ) {
        androidx.test.espresso.Espresso
            .closeSoftKeyboard()
        compose.onNodeWithTag("provider-form-scroll").performScrollToNode(
            hasTestTag("provider-model-add-entry") or hasTestTag("provider-model-manual"),
        )
        if (compose.onAllNodesWithTag("provider-model-add-entry").fetchSemanticsNodes().isNotEmpty()) {
            click("provider-model-add-entry")
        }
        replace("provider-model-manual", id)
        replace("provider-model-display-name", displayName)
        click("provider-models-add")
    }

    private fun scrolled(tag: String): androidx.compose.ui.test.SemanticsNodeInteraction {
        if (compose.onAllNodesWithTag("provider-form-scroll").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("provider-form-scroll").performScrollToNode(hasTestTag(tag))
            return compose
                .onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("provider-form-dialog")))
                .performScrollTo()
        }
        return compose.onNodeWithTag(tag).performScrollTo()
    }

    private fun click(tag: String) {
        val fixed = tag in setOf("provider-form-save", "provider-form-cancel") || tag.startsWith("provider-protocol-")
        val node = if (fixed) compose.onNodeWithTag(tag) else scrolled(tag)
        node.performClick()
    }

    private fun replace(
        tag: String,
        value: String,
    ) = scrolled(tag).performTextReplacement(value)

    private fun awaitClosed() {
        compose.waitUntil(5000) { compose.onAllNodesWithTag("provider-form-dialog").fetchSemanticsNodes().isEmpty() }
    }
}
