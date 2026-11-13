package com.helix.app.provider.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performScrollToNode
import com.helix.app.R
import com.helix.app.provider.ManagedAccountSnapshot
import com.helix.app.provider.ProviderModelsIntegrationDeviceTest
import com.helix.core.model.ModelErrorCode
import com.helix.provider.api.ModelCatalogResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class ProviderModelActionFailureDeviceTest {
    @get:Rule val compose = createComposeRule()
    private var dialogTag = "provider-model-list"

    @Test fun loggedOutSubscriptionShowsEarlyFailureWithoutInventingGenerationEvidence() =
        runBlocking {
            ProviderModelsIntegrationDeviceTest.Fixture(managed = true).use { fixture ->
                fixture.login = fixture.login.copy(state = ManagedAccountSnapshot.State.LOGGED_OUT)
                fixture.service.refreshManagedAccounts()
                showModels(fixture)
                click("provider-model-details-a")
                click("provider-model-test-a")
                awaitEnabled("provider-model-test-a")
                assertFailure(fixture, true)
                assertEquals(0, fixture.generations)
                assertNull(
                    fixture.service.rows.value
                        .single()
                        .modelGenerations["a"],
                )
            }
        }

    @Test fun apiEditorShowsDirectoryFailureEvenWhenEarlierGenerationPassedThenClearsOnRetry() =
        runBlocking {
            ProviderModelsIntegrationDeviceTest.Fixture().use { fixture ->
                fixture.service.saveSelectedModels("p", listOf("a"))
                fixture.service.runConnectionTest("p", "a", verifyGeneration = true)
                val prior =
                    fixture.service.rows.value
                        .single()
                        .modelGenerations["a"]
                val generations = fixture.generations
                fixture.catalog = ModelCatalogResult.Failed(ModelErrorCode.TRANSPORT, "fixture", true)
                compose.setContent {
                    MaterialTheme {
                        Column(Modifier.verticalScroll(rememberScrollState())) { ProviderManager(fixture.service) }
                    }
                }
                compose.onNodeWithTag("provider-manage-models-p").performScrollTo().performClick()
                dialogTag = "provider-form-dialog"
                click("provider-model-details-a")
                click("provider-model-test-a")
                awaitEnabled("provider-model-test-a")
                assertFailure(fixture, true)
                assertEquals(generations, fixture.generations)
                assertEquals(
                    prior,
                    fixture.service.rows.value
                        .single()
                        .modelGenerations["a"],
                )
                fixture.catalog = ModelCatalogResult.Listed(listOf("a"))
                click("provider-model-test-a")
                awaitEnabled("provider-model-test-a")
                assertFailure(fixture, false)
                assertEquals(generations + 1, fixture.generations)
            }
        }

    @Test fun supersededSubscriptionCheckDoesNotShowAnErrorForTheNewAccount() =
        runBlocking {
            ProviderModelsIntegrationDeviceTest.Fixture(managed = true).use { fixture ->
                fixture.service.refreshManagedAccounts()
                val release = CompletableDeferred<Unit>()
                fixture.catalogPause = { release.await() }
                showModels(fixture)
                click("provider-model-details-a")
                click("provider-model-test-a")
                compose.waitUntil(5000) { fixture.catalogCalls == 1 }
                fixture.login = fixture.login.copy(revision = "00000000-0000-0000-0000-000000000004")
                fixture.service.refreshManagedAccounts()
                release.complete(Unit)
                awaitEnabled("provider-model-test-a")
                assertFailure(fixture, false)
                assertNull(
                    fixture.service.rows.value
                        .single()
                        .modelGenerations["a"],
                )
            }
        }

    @Test fun subscriptionCapabilityFailureShowsOperationErrorAndRetryClearsIt() =
        runBlocking {
            ProviderModelsIntegrationDeviceTest.Fixture(managed = true).use { fixture ->
                fixture.service.runConnectionTest("p")
                fixture.capabilityFailure = ModelErrorCode.TRANSPORT
                showModels(fixture)
                click("provider-model-details-a")
                click("provider-model-probe-a")
                awaitEnabled("provider-model-test-a")
                assertFailure(fixture, true)
                fixture.capabilityFailure = null
                click("provider-model-probe-a")
                awaitEnabled("provider-model-test-a")
                assertFailure(fixture, false)
            }
        }

    private fun showModels(fixture: ProviderModelsIntegrationDeviceTest.Fixture) {
        val row =
            fixture.service.rows.value
                .single()
        compose.setContent { MaterialTheme { ProviderModelsDialog(row, fixture.service) {} } }
    }

    private fun assertFailure(
        fixture: ProviderModelsIntegrationDeviceTest.Fixture,
        present: Boolean,
    ) {
        if (dialogTag == "provider-model-list") {
            compose.onNodeWithTag(dialogTag).performScrollToKey("result")
        }
        val message = compose.onNodeWithText(failureLabel(fixture))
        if (present) message.assertExists() else message.assertDoesNotExist()
    }

    private fun failureLabel(fixture: ProviderModelsIntegrationDeviceTest.Fixture) =
        fixture.context.getString(R.string.provider_models_operation_failed)

    private fun dialogNode(tag: String) = hasTestTag(tag) and hasAnyAncestor(hasTestTag(dialogTag))

    private fun click(tag: String) {
        if (dialogTag == "provider-model-list") {
            compose.onNodeWithTag(dialogTag).performScrollToKey("model-a")
        } else {
            compose.onNodeWithTag("provider-form-scroll").performScrollToNode(hasTestTag(tag))
        }
        compose.onNode(dialogNode(tag)).performScrollTo().performClick()
    }

    private fun awaitEnabled(tag: String) {
        compose.waitUntil(5000) {
            compose
                .onAllNodes(dialogNode(tag))
                .fetchSemanticsNodes()
                .singleOrNull()
                ?.config
                ?.contains(SemanticsProperties.Disabled) == false
        }
        compose.waitForIdle()
    }
}
