package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.helix.app.provider.ConnectionTestStatus
import com.helix.app.provider.ManagedAccountSnapshot
import com.helix.app.provider.ProviderModelSelection
import com.helix.app.provider.ProviderRowUi
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ProviderProvisioningKind
import com.helix.core.model.ProviderResidence
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** No real account, test request or external browser. Exercises presentation and navigation, not provider readiness. */
class SubscriptionSetupUiDeviceTest {
    @get:Rule val compose = createComposeRule()

    private fun row() =
        ProviderRowUi(
            "subscription-fixture",
            "Fixture account",
            ProviderProtocol.OPENAI_RESPONSES,
            "https://example.invalid",
            ProviderResidence.PUBLIC_CLOUD,
            "m",
            false,
            false,
            ConnectionTestStatus.Untested,
            null,
            null,
            emptyList(),
            provisioning = ProviderProvisioningKind.MANAGED_ACCOUNT,
            accountState =
                ManagedAccountSnapshot(
                    ManagedAccountSnapshot.State.LOGGED_IN,
                    "12345678-1234-1234-1234-123456789abc",
                ),
        )

    @Test fun untestedAccountWithNoSelectedModelsStillOffersSetupNavigation() {
        var navigations = 0
        compose.setContent {
            MaterialTheme {
                ComposerModelMenu(
                    listOf(
                        row(),
                    ),
                    null,
                    null,
                    true,
                    { _, _ -> error("must not select") },
                    onManageModels = {
                        navigations++
                    },
                )
            }
        }
        compose.onNodeWithTag("chat-model-menu").performClick()
        compose.onNodeWithTag("chat-model-setup-subscription-fixture").assertIsDisplayed()
        compose.onNodeWithTag("chat-model-setup-open-subscription-fixture").performClick()
        compose.runOnIdle { assertEquals(1, navigations) }
        compose.onNodeWithTag("chat-model-setup-subscription-fixture").assertDoesNotExist()
    }

    @Test fun preselectedUntestedModelStaysDisabled() {
        compose.setContent {
            MaterialTheme {
                ComposerModelMenu(
                    listOf(row().copy(modelSelection = ProviderModelSelection(listOf("m"), true))),
                    null,
                    null,
                    true,
                    { _, _ -> error("must not select") },
                )
            }
        }
        compose.onNodeWithTag("chat-model-menu").performClick()
        compose.onNodeWithTag("chat-model-subscription-fixture-m").assertIsNotEnabled()
    }

    @Test fun highlightedActionStillRequiresAnExplicitClick() {
        var tests = 0
        compose.setContent { MaterialTheme { ProviderConnectionTestAction(row(), false, false) { tests++ } } }
        compose.runOnIdle { assertEquals(0, tests) }
        compose.onNodeWithTag("provider-test").performClick()
        compose.runOnIdle { assertEquals(1, tests) }
    }

    @Test fun busyTestCannotBeStartedAgain() {
        compose.setContent {
            MaterialTheme {
                ProviderConnectionTestAction(
                    row(),
                    true,
                    false,
                ) { error("duplicate test") }
            }
        }
        compose.onNodeWithTag("provider-test").assertIsNotEnabled()
    }
}
