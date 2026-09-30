package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.helix.app.provider.ConnectionTestStatus
import com.helix.app.provider.ProviderModelSelection
import com.helix.app.provider.ProviderRowUi
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ProviderProvisioningKind
import com.helix.core.model.ProviderResidence
import com.helix.provider.api.CapabilitySource
import com.helix.provider.api.ProviderCapabilities
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Compose-only fixtures. Candidate rendering and raw selection IDs are checked independently. */
class ProviderModelPickerDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val caps = ProviderCapabilities(true, true, false, false, false, false, 8192, CapabilitySource.PROBED)

    @Test fun hiddenCurrentIsExplainedButNotReinsertedIntoAnEmptyPicker() {
        val row = row("api", ProviderProvisioningKind.USER_CONFIGURED, "a")
        compose.setContent {
            MaterialTheme {
                ComposerModelMenu(listOf(row), row.id, "a", true) { _, _ -> error("No candidate selected") }
            }
        }
        compose.onNodeWithTag("chat-model-menu").performClick()
        compose.onNodeWithTag("chat-model-hidden-current").assertIsDisplayed()
        compose.onNodeWithTag("chat-model-empty").assertIsDisplayed()
        compose.onNodeWithTag("chat-model-api-a").assertDoesNotExist()
    }

    @Test fun subscriptionAndLocalChoicesUseTheSamePickerWithFriendlyLocalNames() {
        val localId = "a".repeat(64)
        val subscription =
            row("subscription", ProviderProvisioningKind.MANAGED_ACCOUNT, "a")
                .copy(modelSelection = ProviderModelSelection(listOf("b"), "b", true))
        val local =
            row("local", ProviderProvisioningKind.ON_DEVICE_ASSET, localId)
                .copy(
                    displayName = "Local fixture",
                    modelSelection = ProviderModelSelection(listOf(localId), localId, true),
                )
        var selected: Pair<String, String>? = null
        compose.setContent {
            MaterialTheme {
                ComposerModelMenu(listOf(subscription, local), null, null, true) { provider, model ->
                    selected =
                        provider to model
                }
            }
        }
        compose.onNodeWithTag("chat-model-menu").performClick()
        compose.onNodeWithTag("chat-model-subscription-a").assertDoesNotExist()
        compose.onNodeWithTag("chat-model-subscription-b").assertIsDisplayed()
        compose.onNodeWithText(localId).assertDoesNotExist()
        compose.onNodeWithTag("chat-model-local-$localId").performClick()
        compose.runOnIdle { assertEquals("local" to localId, selected) }
    }

    private fun row(
        id: String,
        kind: ProviderProvisioningKind,
        model: String,
    ) = ProviderRowUi(
        id,
        id,
        ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
        "https://example.test",
        ProviderResidence.PUBLIC_CLOUD,
        model,
        false,
        false,
        ConnectionTestStatus.Passed(1, caps, listOf(model, "b")),
        caps,
        listOf(model, "b"),
        emptyList(),
        provisioning = kind,
    )
}
