package com.helix.app.provider.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import com.helix.app.provider.ProviderModelsIntegrationDeviceTest
import com.helix.core.model.ProviderProtocol
import com.helix.core.storage.repository.ProviderConfigSpec
import com.helix.provider.api.ModelCatalogResult
import com.helix.provider.api.ProviderCapabilities
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The model dialog has one draft/save contract for API and managed-account sources. */
class ProviderModelsDialogDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun apiCloseDiscardsModelDraft() = closeDiscardsModelDraft(false)

    @Test fun subscriptionCloseDiscardsModelDraft() = closeDiscardsModelDraft(true)

    private fun closeDiscardsModelDraft(managed: Boolean) =
        runBlocking {
            ProviderModelsIntegrationDeviceTest.Fixture(managed = managed).use { f ->
                prepare(f)
                val baseline =
                    f.service.rows.value
                        .single()
                        .modelSelection
                show(f)
                assertNoRequests(f)
                click("provider-model-choice-b")
                click("provider-model-details-b")
                replace("provider-model-name-b", "Unsaved name")
                addModel("manual", "Unsaved custom")
                assertEquals(
                    baseline,
                    f.service.rows.value
                        .single()
                        .modelSelection,
                )
                click("provider-models-close")
                awaitClosed()
                assertEquals(
                    baseline,
                    f.service.rows.value
                        .single()
                        .modelSelection,
                )
                assertEquals("a", f.service.storedConfig("p").model)
                assertNoRequests(f)
            }
        }

    @Test fun apiSaveCommitsSelectionAndNamesOnly() = saveCommitsSelectionAndNamesOnly(false)

    @Test fun subscriptionSaveCommitsSelectionAndNamesOnly() = saveCommitsSelectionAndNamesOnly(true)

    private fun saveCommitsSelectionAndNamesOnly(managed: Boolean) =
        runBlocking {
            ProviderModelsIntegrationDeviceTest.Fixture(managed = managed).use { f ->
                prepare(f)
                val config = f.service.storedConfig("p")
                show(f)
                click("provider-model-choice-a")
                click("provider-model-choice-b")
                click("provider-model-details-b")
                replace("provider-model-name-b", "Second model")
                addModel("manual", "Custom model")
                click("provider-models-save")
                awaitClosed()
                val saved =
                    f.service.rows.value
                        .single()
                        .modelSelection
                assertEquals(listOf("b", "manual"), saved.models)
                assertEquals("Second model", saved.displayNames["b"])
                assertEquals("Custom model", saved.displayNames["manual"])
                assertEquals(config, f.service.storedConfig("p"))
                assertNoRequests(f)
            }
        }

    @Test fun apiModelActionsDoNotCommitPendingSelection() = modelActionsDoNotCommitPendingSelection(false)

    @Test fun subscriptionModelActionsDoNotCommitPendingSelection() = modelActionsDoNotCommitPendingSelection(true)

    private fun modelActionsDoNotCommitPendingSelection(managed: Boolean) =
        runBlocking {
            ProviderModelsIntegrationDeviceTest.Fixture(managed = managed).use { f ->
                prepare(f)
                val baseline =
                    f.service.rows.value
                        .single()
                        .modelSelection
                show(f)
                assertNoRequests(f)
                click("provider-model-choice-b")
                click("provider-model-details-b")
                click("provider-model-test-b")
                compose.waitUntil(5000) {
                    f.service.rows.value
                        .single()
                        .modelGenerations
                        .containsKey("b")
                }
                assertEquals(listOf("b"), f.requests.map { it.model })
                assertPersistedSelection(f, baseline)
                click("provider-model-probe-b")
                compose.waitUntil(5000) {
                    f.service.rows.value
                        .single()
                        .modelVerifications
                        .containsKey("b")
                }
                assertEquals(listOf("b"), f.probed)
                assertPersistedSelection(f, baseline)
                click("provider-model-context-b")
                compose.onNodeWithTag("provider-context-model").assertTextContains("b")
                compose.onNodeWithTag("provider-context-choice-a").assertDoesNotExist()
                compose.onNodeWithTag("provider-context-close").performClick()
                assertPersistedSelection(f, baseline)
                assertEquals("a", f.service.storedConfig("p").model)
                click("provider-models-close")
                awaitClosed()
                assertPersistedSelection(f, baseline)
            }
        }

    @Test fun apiDirectoryRefreshPreservesPendingChoices() = directoryRefreshPreservesPendingChoices(false)

    @Test fun subscriptionDirectoryRefreshPreservesPendingChoices() = directoryRefreshPreservesPendingChoices(true)

    private fun directoryRefreshPreservesPendingChoices(managed: Boolean) =
        runBlocking {
            ProviderModelsIntegrationDeviceTest.Fixture(managed = managed).use { f ->
                prepare(f)
                show(f)
                click("provider-model-choice-b")
                f.catalog = ModelCatalogResult.Listed(listOf("fresh"))
                click("provider-discover-models")
                compose.waitUntil(5000) {
                    f.service.rows.value
                        .single()
                        .backendModels == listOf("fresh")
                }
                scrolled("provider-model-choice-a").assertIsOn()
                scrolled("provider-model-choice-b").assertIsOn()
                scrolled("provider-model-choice-fresh").assertExists()
                assertEquals(
                    listOf("a"),
                    f.service.rows.value
                        .single()
                        .modelSelection.models,
                )
                assertEquals(1, f.catalogCalls)
                assertEquals(0, f.generations)
                assertEquals(emptyList<String>(), f.probed)
                click("provider-models-save")
                awaitClosed()
                assertEquals(
                    listOf("a", "b"),
                    f.service.rows.value
                        .single()
                        .modelSelection.models,
                )
            }
        }

    @Test fun apiConfigurationChangeDisablesOldModelActions() = configurationChangeDisablesOldModelActions(false)

    @Test fun subscriptionConfigurationChangeDisablesOldModelActions() =
        configurationChangeDisablesOldModelActions(
            true,
        )

    private fun configurationChangeDisablesOldModelActions(managed: Boolean) =
        runBlocking {
            ProviderModelsIntegrationDeviceTest.Fixture(managed = managed).use { f ->
                prepare(f)
                show(f)
                click("provider-model-details-a")
                val old = f.storage.providerConfigs.resolve("p")
                f.storage.providerConfigs.overwrite(
                    ProviderConfigSpec(
                        id = old.id,
                        displayName = old.displayName,
                        protocol = ProviderProtocol.parse(requireNotNull(old.protocol)),
                        endpoint = "https://changed.test/v1",
                        model = old.model,
                        headersJson = old.headersJson,
                        secretAlias = old.secretAlias,
                        provisioningKind = old.provisioningKind,
                        transportKind = old.transportKind,
                        authKind = old.authKind,
                        capabilitySnapshot = old.capabilitySnapshot,
                    ),
                )
                f.service.refresh()
                compose.waitUntil(5000) {
                    f.service.rows.value
                        .single()
                        .configuration
                        ?.endpoint
                        ?.host == "changed.test"
                }
                scrolled("provider-model-test-a").assertIsNotEnabled()
                scrolled("provider-model-probe-a").assertIsNotEnabled()
                scrolled("provider-model-context-a").assertIsNotEnabled()
                compose.onNodeWithTag("provider-models-save").assertIsNotEnabled()
                assertNoRequests(f)
                click("provider-models-close")
                awaitClosed()
            }
        }

    private fun assertPersistedSelection(
        fixture: ProviderModelsIntegrationDeviceTest.Fixture,
        expected: com.helix.app.provider.ProviderModelSelection,
    ) {
        assertEquals(
            expected,
            fixture.service.rows.value
                .single()
                .modelSelection,
        )
    }

    private suspend fun prepare(f: ProviderModelsIntegrationDeviceTest.Fixture) {
        // Seed an already-known catalog without issuing a discovery request on dialog open.
        f.service.saveSelectedModels("p", listOf("a"))
        f.status.recordPassed(
            "p",
            1,
            ProviderCapabilities.parse(f.service.storedConfig("p").capabilitySnapshot),
            listOf("a", "b"),
        )
        f.service.saveSelectedModels("p", listOf("a"))
    }

    private fun show(f: ProviderModelsIntegrationDeviceTest.Fixture) {
        val row =
            f.service.rows.value
                .single()
        val open = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (open.value) ProviderModelsDialog(row, f.service) { open.value = false }
            }
        }
        compose.onNodeWithTag("provider-models-save").assertExists()
    }

    private fun assertNoRequests(f: ProviderModelsIntegrationDeviceTest.Fixture) {
        compose.runOnIdle {
            assertEquals(0, f.catalogCalls)
            assertEquals(0, f.generations)
            assertEquals(emptyList<String>(), f.probed)
            assertEquals(0, f.metadataCalls)
            assertEquals(0, f.contextCalls)
        }
    }

    private fun addModel(
        id: String,
        name: String,
    ) {
        // Dismiss the edited name's IME before scrolling to another lazy item.
        androidx.test.espresso.Espresso
            .closeSoftKeyboard()
        click("provider-model-add-entry")
        replace("provider-model-manual", id)
        replace("provider-model-display-name", name)
        click("provider-models-add")
    }

    private fun scrolled(tag: String): androidx.compose.ui.test.SemanticsNodeInteraction {
        compose.onNodeWithTag("provider-model-list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo()
    }

    private fun click(tag: String) {
        val node =
            if (tag in setOf("provider-models-save", "provider-models-close")) {
                compose.onNodeWithTag(tag)
            } else {
                scrolled(tag)
            }
        node.performClick()
    }

    private fun replace(
        tag: String,
        value: String,
    ) = scrolled(tag).performTextReplacement(value)

    private fun awaitClosed() {
        compose.waitUntil(5000) { compose.onAllNodesWithTag("provider-models-save").fetchSemanticsNodes().isEmpty() }
    }
}
