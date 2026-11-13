package com.helix.app.provider.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.helix.app.provider.ConnectionTestStatus
import com.helix.app.provider.ManagedAccountSnapshot
import com.helix.app.provider.ProviderModelsIntegrationDeviceTest
import com.helix.core.model.ModelErrorCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The production dialog and service, with an isolated Room store and synthetic model. */
class ProviderAccountDialogDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun checkPersistsAndReopeningDoesNotRunAnotherCheck() =
        runBlocking {
            ProviderModelsIntegrationDeviceTest.Fixture(managed = true).use { fixture ->
                fixture.service.refreshManagedAccounts()
                val open = mutableStateOf(true)
                val initial =
                    fixture.service.rows.value
                        .single()
                compose.setContent {
                    MaterialTheme {
                        if (open.value) ProviderAccountDialog(initial, fixture.service) { open.value = false }
                    }
                }
                compose.onNodeWithTag("provider-account-untested").assertExists()
                assertEquals(0, fixture.generations)
                click("provider-account-test")
                compose.waitUntil(5000) { fixture.status.statusFor("p") is ConnectionTestStatus.Passed }
                compose.waitForIdle()
                compose.onNodeWithTag("provider-account-passed").assertExists()
                val checked = fixture.status.statusFor("p")
                val requests = fixture.generations
                click("provider-account-close")
                compose.onNodeWithTag("provider-account-dialog").assertDoesNotExist()
                compose.runOnIdle { open.value = true }
                compose.onNodeWithTag("provider-account-passed").assertExists()
                assertEquals(checked, fixture.status.statusFor("p"))
                assertEquals(requests, fixture.generations)
                assertTrue(
                    fixture.service.rows.value
                        .single()
                        .modelSelection.models
                        .isEmpty(),
                )
            }
        }

    @Test fun failedCheckShowsDurableFailureAndRetryCanRecover() =
        runBlocking {
            ProviderModelsIntegrationDeviceTest.Fixture(managed = true).use { fixture ->
                fixture.service.refreshManagedAccounts()
                fixture.generationFailure = ModelErrorCode.AUTH
                show(fixture)
                click("provider-account-test")
                compose.waitUntil(5000) { fixture.status.statusFor("p") is ConnectionTestStatus.Failed }
                compose.waitForIdle()
                compose.onNodeWithTag("provider-account-failed").assertExists()
                fixture.generationFailure = null
                click("provider-account-test")
                compose.waitUntil(5000) { fixture.status.statusFor("p") is ConnectionTestStatus.Passed }
                compose.waitForIdle()
                compose.onNodeWithTag("provider-account-passed").assertExists()
                compose.onNodeWithTag("provider-account-operation-failed").assertDoesNotExist()
            }
        }

    @Test fun logoutInvalidatesVisibleSuccessAndCheckCannotUseOldAccount() =
        runBlocking {
            ProviderModelsIntegrationDeviceTest.Fixture(managed = true).use { fixture ->
                fixture.service.runConnectionTest("p")
                show(fixture)
                compose.onNodeWithTag("provider-account-passed").assertExists()
                val requests = fixture.generations
                fixture.login = ManagedAccountSnapshot(ManagedAccountSnapshot.State.LOGGED_OUT, fixture.login.revision)
                fixture.service.refreshManagedAccounts()
                compose.waitForIdle()
                compose.onNodeWithTag("provider-account-passed").assertDoesNotExist()
                click("provider-account-test")
                compose.waitUntil(5000) {
                    compose.onAllNodesWithTag("provider-account-operation-failed").fetchSemanticsNodes().isNotEmpty()
                }
                assertEquals(requests, fixture.generations)
                assertTrue(fixture.status.statusFor("p") is ConnectionTestStatus.Untested)
            }
        }

    @Test fun removedSourceCannotStartAnyAccountOperation() =
        runBlocking {
            ProviderModelsIntegrationDeviceTest.Fixture(managed = true).use { fixture ->
                fixture.service.refreshManagedAccounts()
                show(fixture)
                fixture.storage.providerConfigs.delete("p")
                fixture.service.refresh()
                compose.waitUntil(5000) {
                    fixture.service.rows.value
                        .isEmpty()
                }
                compose.waitForIdle()
                compose.onNodeWithTag("provider-account-login").assertIsNotEnabled()
                compose.onNodeWithTag("provider-account-test").assertIsNotEnabled()
                compose.onNodeWithTag("provider-account-operation-failed").assertExists()
                assertEquals(0, fixture.generations)
            }
        }

    @Test fun busyCheckRejectsRepeatedClickAndCloseThenReleasesControls() =
        runBlocking {
            ProviderModelsIntegrationDeviceTest.Fixture(managed = true).use { fixture ->
                fixture.service.refreshManagedAccounts()
                val release = CompletableDeferred<Unit>()
                fixture.catalogPause = { release.await() }
                show(fixture)
                try {
                    click("provider-account-test")
                    compose.waitUntil(5000) { fixture.catalogCalls == 1 }
                    compose.onNodeWithTag("provider-account-test").assertIsNotEnabled().performClick()
                    compose.onNodeWithTag("provider-account-close").assertIsNotEnabled().performClick()
                    compose.onNodeWithTag("provider-account-login").assertIsNotEnabled()
                    compose.onNodeWithTag("provider-account-dialog").assertExists()
                    assertEquals(0, fixture.generations)
                } finally {
                    release.complete(Unit)
                }
                compose.waitUntil(5000) { fixture.status.statusFor("p") is ConnectionTestStatus.Passed }
            }
        }

    @Test fun leavingCompositionCancelsCheckWithoutPublishingSuccess() =
        runBlocking {
            ProviderModelsIntegrationDeviceTest.Fixture(managed = true).use { fixture ->
                fixture.service.refreshManagedAccounts()
                val release = CompletableDeferred<Unit>()
                val exited = CompletableDeferred<Unit>()
                fixture.catalogPause = {
                    try {
                        release.await()
                    } finally {
                        exited.complete(Unit)
                    }
                }
                val open = mutableStateOf(true)
                val row =
                    fixture.service.rows.value
                        .single()
                compose.setContent {
                    MaterialTheme {
                        if (open.value) ProviderAccountDialog(row, fixture.service) { open.value = false }
                    }
                }
                click("provider-account-test")
                compose.waitUntil(5000) { fixture.catalogCalls == 1 }
                compose.runOnIdle { open.value = false }
                compose.waitUntil(5000) { exited.isCompleted }
                assertTrue(fixture.status.statusFor("p") is ConnectionTestStatus.Untested)
                release.complete(Unit)
                compose.runOnIdle { open.value = true }
                compose.onNodeWithTag("provider-account-untested").assertExists()
                assertEquals(0, fixture.generations)
            }
        }

    @Test fun accountChangeDuringCheckDoesNotAttachOldResultToNewLogin() =
        runBlocking {
            ProviderModelsIntegrationDeviceTest.Fixture(managed = true).use { fixture ->
                fixture.service.refreshManagedAccounts()
                val release = CompletableDeferred<Unit>()
                fixture.catalogPause = { release.await() }
                show(fixture)
                click("provider-account-test")
                compose.waitUntil(5000) { fixture.catalogCalls == 1 }
                fixture.login = fixture.login.copy(revision = "00000000-0000-0000-0000-000000000003")
                fixture.service.refreshManagedAccounts()
                release.complete(Unit)
                compose.waitUntil(5000) {
                    !compose
                        .onNodeWithTag("provider-account-test")
                        .fetchSemanticsNode()
                        .config
                        .contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
                }
                compose.onNodeWithTag("provider-account-passed").assertDoesNotExist()
                compose.onNodeWithTag("provider-account-operation-failed").assertDoesNotExist()
                assertTrue(fixture.status.statusFor("p") is ConnectionTestStatus.Untested)
            }
        }

    private fun show(fixture: ProviderModelsIntegrationDeviceTest.Fixture) {
        val row =
            fixture.service.rows.value
                .single()
        compose.setContent { MaterialTheme { ProviderAccountDialog(row, fixture.service) {} } }
    }

    private fun click(tag: String) {
        val node = compose.onNodeWithTag(tag)
        if (tag != "provider-account-close") node.performScrollTo()
        node.performClick()
    }
}
