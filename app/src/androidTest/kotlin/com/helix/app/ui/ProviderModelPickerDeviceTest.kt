package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.helix.app.provider.ConnectionTestStatus
import com.helix.app.provider.ProviderModelSelection
import com.helix.app.provider.ProviderRowUi
import com.helix.app.provider.ui.ProviderModelChoiceRow
import com.helix.app.provider.ui.ProviderRow
import com.helix.app.provider.ui.ProviderRowActions
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

    @Test fun optionalProviderControlsRequireExpansionAndNeverRunOnOpen() {
        var probes = 0
        var visionChanges = 0
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ProviderRow(
                        row("api", ProviderProvisioningKind.USER_CONFIGURED, "a"),
                        false,
                        ProviderRowActions({}, {}, {}, { visionChanges++ }, {}, onDetectCapabilities = { probes++ }),
                        false,
                    )
                }
            }
        }
        compose.onNodeWithTag("provider-manage-models-api").assertIsDisplayed()
        compose.onNodeWithTag("provider-test").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("provider-context-api").assertDoesNotExist()
        compose.onNodeWithTag("provider-capabilities").assertDoesNotExist()
        compose.onNodeWithTag("provider-vision-declare").assertDoesNotExist()
        compose.onNodeWithTag("provider-options-api").performScrollTo().performClick()
        compose.onNodeWithTag("provider-context-api").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("provider-capabilities").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("provider-vision-declare").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, probes)
            assertEquals(0, visionChanges)
        }
        compose.onNodeWithTag("provider-options-api").performScrollTo().performClick()
        compose.onNodeWithTag("provider-capabilities").assertDoesNotExist()
    }

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
            org.junit.Assert.assertTrue(login < connection && connection < models)
        }
    }

    @Test fun hiddenCurrentIsExplainedButNotReinsertedIntoAnEmptyPicker() {
        val row = row("api", ProviderProvisioningKind.USER_CONFIGURED, "a")
        compose.setContent {
            MaterialTheme {
                ComposerModelMenu(listOf(row), row.id, "a", true, onSave = {
                    _,
                    _,
                    _,
                    ->
                    error("No candidate selected")
                })
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
                .copy(
                    modelSelection = ProviderModelSelection(listOf("b"), configured = true),
                    accountState =
                        com.helix.app.provider.ManagedAccountSnapshot(
                            com.helix.app.provider.ManagedAccountSnapshot.State.LOGGED_IN,
                            "12345678-1234-1234-1234-123456789abc",
                        ),
                )
        val local =
            row("local", ProviderProvisioningKind.ON_DEVICE_ASSET, localId)
                .copy(
                    displayName = "Local fixture",
                    modelSelection = ProviderModelSelection(listOf(localId), configured = true),
                )
        var selected: Pair<String, String>? = null
        compose.setContent {
            MaterialTheme {
                ComposerModelMenu(listOf(subscription, local), null, null, true, onSave = { provider, model, _ ->
                    selected =
                        provider to model
                    com.helix.app.chat.SessionModelSelectionResult.APPLIED
                })
            }
        }
        compose.onNodeWithTag("chat-model-menu").performClick()
        compose.onNodeWithTag("chat-model-subscription-a").assertDoesNotExist()
        compose.onNodeWithTag("chat-model-subscription-b").assertIsDisplayed()
        compose.onNodeWithText(localId).assertDoesNotExist()
        compose.onNodeWithTag("chat-model-local-$localId").performClick()
        compose.runOnIdle { assertEquals(null, selected) }
        compose.onNodeWithTag("chat-model-picker-save").performClick()
        compose.runOnIdle { assertEquals("local" to localId, selected) }
    }

    @Test fun loggedOutSubscriptionDoesNotAppearAsACandidateOrSetupRow() {
        val subscription =
            row("logged-out", ProviderProvisioningKind.MANAGED_ACCOUNT, "a")
                .copy(modelSelection = ProviderModelSelection(listOf("a"), configured = true))
        compose.setContent {
            MaterialTheme {
                ComposerModelMenu(listOf(subscription), null, null, true, onSave = { _, _, _ -> error("Hidden") })
            }
        }
        compose.onNodeWithTag("chat-model-menu").performClick()
        compose.onNodeWithTag("chat-model-logged-out-a").assertDoesNotExist()
        compose.onNodeWithText("logged-out").assertDoesNotExist()
        compose.onNodeWithTag("chat-model-empty").assertIsDisplayed()
    }

    @Test fun reasoningWaitsForSaveAndRejectedSaveKeepsTheDialog() {
        val current = androidx.compose.runtime.mutableStateOf("a")
        val actualReasoning = androidx.compose.runtime.mutableStateOf(com.helix.core.model.ReasoningEffort.OFF)
        var succeeds = false
        val row = reasoningFixtureRow()
        var requested: String? = null
        compose.setContent {
            MaterialTheme {
                ComposerModelMenu(listOf(row), row.id, current.value, true, { _, model, reasoning ->
                    requested = model
                    if (succeeds) {
                        current.value = model
                        actualReasoning.value = reasoning
                        com.helix.app.chat.SessionModelSelectionResult.APPLIED
                    } else {
                        com.helix.app.chat.SessionModelSelectionResult.FAILED
                    }
                }, reasoning = actualReasoning.value)
            }
        }
        compose.onNodeWithTag("chat-reasoning-menu").assertDoesNotExist()
        compose.onNodeWithTag("chat-model-menu").performClick()
        compose.onNodeWithTag("chat-reasoning-menu").assertIsDisplayed()
        compose.onNodeWithTag("chat-model-api-b").performClick()
        compose.runOnIdle {
            assertEquals(null, requested)
            assertEquals("a", current.value)
        }
        compose.onNodeWithTag("chat-reasoning-menu").assertIsDisplayed().performClick()
        compose.onNodeWithTag("chat-reasoning-medium").performClick()
        compose.runOnIdle { assertEquals(com.helix.core.model.ReasoningEffort.OFF, actualReasoning.value) }
        compose.onNodeWithTag("chat-model-picker-save").performClick()
        compose.onNodeWithTag("chat-model-save-error").assertExists()
        compose.runOnIdle {
            assertEquals("a", current.value)
            succeeds = true
        }
        compose.onNodeWithTag("chat-model-picker-save").performClick()
        compose.onNodeWithTag("chat-model-search").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("b", current.value)
            assertEquals(com.helix.core.model.ReasoningEffort.MEDIUM, actualReasoning.value)
        }
    }

    private fun reasoningFixtureRow(): ProviderRowUi =
        row("api", ProviderProvisioningKind.USER_CONFIGURED, "a")
            .copy(
                modelSelection = ProviderModelSelection(listOf("a", "b"), configured = true),
                modelMetadata =
                    mapOf(
                        "a" to
                            com.helix.provider.api.ModelMetadata(
                                reasoningEfforts =
                                    com.helix.core.model.ReasoningEffort.FALLBACK
                                        .drop(1),
                            ),
                        "b" to
                            com.helix.provider.api.ModelMetadata(
                                reasoningEfforts =
                                    com.helix.core.model.ReasoningEffort.FALLBACK
                                        .drop(1),
                            ),
                    ),
            )

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
