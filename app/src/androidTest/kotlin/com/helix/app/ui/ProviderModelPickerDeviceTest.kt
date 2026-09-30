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

    @Test fun modelNameTogglesTheOnlySelectionControl() {
        val selected = androidx.compose.runtime.mutableStateOf(ProviderModelSelection())
        val busy = androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                ProviderModelChoiceRow(
                    row("subscription", ProviderProvisioningKind.MANAGED_ACCOUNT, "a"),
                    "a",
                    selected.value,
                    busy.value,
                    false,
                    { selected.value = selected.value.toggle("a", it) },
                    {},
                    {},
                    {},
                    {},
                    {},
                )
            }
        }
        compose.onNodeWithText("a").performClick()
        compose.runOnIdle { assertEquals(listOf("a"), selected.value.models) }
        compose.onNodeWithTag("provider-model-default-a").assertDoesNotExist()
        compose.onNodeWithText("a").performClick()
        compose.runOnIdle {
            assertEquals(emptyList<String>(), selected.value.models)
            busy.value = true
        }
        compose.onNodeWithText("a").performClick()
        compose.runOnIdle { assertEquals(emptyList<String>(), selected.value.models) }
    }

    @Test fun allSubscriptionsKeepOnlyAccountAndModelManagementActions() {
        val source = androidx.compose.runtime.mutableStateOf("codex")
        compose.setContent {
            MaterialTheme {
                ProviderRow(
                    row(source.value, ProviderProvisioningKind.MANAGED_ACCOUNT, "a"),
                    false,
                    ProviderRowActions({}, {}, {}, {}, {}),
                    false,
                )
            }
        }
        listOf("codex", "grok", "claude", "antigravity", "copilot").forEach { id ->
            compose.runOnIdle { source.value = id }
            compose.onNodeWithTag("provider-context-$id").assertDoesNotExist()
            compose.onNodeWithTag("provider-capabilities").assertDoesNotExist()
            val login =
                compose
                    .onNodeWithTag("provider-manage-account")
                    .fetchSemanticsNode()
                    .boundsInRoot.top
            val models =
                compose
                    .onNodeWithTag("provider-manage-models-$id")
                    .fetchSemanticsNode()
                    .boundsInRoot.top
            val connection =
                compose
                    .onNodeWithTag("provider-test")
                    .fetchSemanticsNode()
                    .boundsInRoot.top
            org.junit.Assert.assertTrue(login < models && models < connection)
        }
    }

    @Test fun hiddenCurrentIsExplainedButNotReinsertedIntoAnEmptyPicker() {
        val row = row("api", ProviderProvisioningKind.USER_CONFIGURED, "a")
        compose.setContent {
            MaterialTheme {
                ComposerModelMenu(listOf(row), row.id, "a", true, onSelect = { _, _ -> error("No candidate selected") })
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
                .copy(modelSelection = ProviderModelSelection(listOf("b"), configured = true))
        val local =
            row("local", ProviderProvisioningKind.ON_DEVICE_ASSET, localId)
                .copy(
                    displayName = "Local fixture",
                    modelSelection = ProviderModelSelection(listOf(localId), configured = true),
                )
        var selected: Pair<String, String>? = null
        compose.setContent {
            MaterialTheme {
                ComposerModelMenu(listOf(subscription, local), null, null, true, onSelect = { provider, model ->
                    selected =
                        provider to model
                })
            }
        }
        compose.onNodeWithTag("chat-model-menu").performClick()
        compose.onNodeWithTag("chat-model-subscription-a").assertDoesNotExist()
        compose.onNodeWithTag("chat-model-subscription-b").assertIsDisplayed()
        compose.onNodeWithText(localId).assertDoesNotExist()
        compose.onNodeWithTag("chat-model-local-$localId").performClick()
        compose.runOnIdle { assertEquals("local" to localId, selected) }
    }

    @Test fun reasoningWaitsForTheSelectedModelToBeApplied() {
        val current = androidx.compose.runtime.mutableStateOf("a")
        val row =
            row("api", ProviderProvisioningKind.USER_CONFIGURED, "a")
                .copy(modelSelection = ProviderModelSelection(listOf("a", "b"), configured = true))
        var requested: String? = null
        compose.setContent {
            MaterialTheme {
                ComposerModelMenu(listOf(row), row.id, current.value, true, { _, model -> requested = model }) {
                    ComposerReasoningMenu(com.helix.core.model.ReasoningEffort.OFF, true, {})
                }
            }
        }
        compose.onNodeWithTag("chat-reasoning-menu").assertDoesNotExist()
        compose.onNodeWithTag("chat-model-menu").performClick()
        compose.onNodeWithTag("chat-reasoning-menu").assertIsDisplayed()
        compose.onNodeWithTag("chat-model-api-b").performClick()
        compose.runOnIdle { assertEquals("b", requested) }
        compose.onNodeWithTag("chat-reasoning-menu").assertDoesNotExist()
        // A rejected or delayed switch can return to the actual model without reapplying it.
        compose.onNodeWithTag("chat-model-api-a").performClick()
        compose.onNodeWithTag("chat-reasoning-menu").assertIsDisplayed()
        compose.runOnIdle { assertEquals("b", requested) }
        compose.onNodeWithTag("chat-model-api-b").performClick()
        compose.runOnIdle { current.value = "b" }
        compose.onNodeWithTag("chat-reasoning-menu").assertIsDisplayed().performClick()
        compose.onNodeWithTag("chat-reasoning-medium").assertIsDisplayed()
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
