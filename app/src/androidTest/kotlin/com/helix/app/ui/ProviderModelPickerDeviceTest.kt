package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.helix.app.infrastructure.conversation.conversationModelSources
import com.helix.app.provider.ConnectionTestStatus
import com.helix.app.provider.ProviderModelSelection
import com.helix.app.provider.ProviderRowUi
import com.helix.app.provider.ui.ProviderModelChoiceRow
import com.helix.app.provider.ui.ProviderRow
import com.helix.app.provider.ui.ProviderRowActions
import com.helix.app.provider.ui.ProviderSelectedModelList
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ProviderProvisioningKind
import com.helix.core.model.ProviderResidence
import com.helix.feature.conversation.presentation.ComposerModelMenu
import com.helix.feature.conversation.presentation.SessionModelSelectionResult
import com.helix.provider.api.CapabilitySource
import com.helix.provider.api.ProviderCapabilities
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Compose-only fixtures. Candidate rendering and raw selection IDs are checked independently. */
class ProviderModelPickerDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val caps = ProviderCapabilities(true, true, false, false, false, false, 8192, CapabilitySource.PROBED)

    @Test fun selectedModelsExposeExactModelActionsWithoutGlobalOptionsOrAutomaticRequests() {
        val source = androidx.compose.runtime.mutableStateOf(ProviderProvisioningKind.USER_CONFIGURED)
        val calls = mutableListOf<Pair<String, String>>()
        var connections = 0
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ProviderRow(
                        selectedRow("source", source.value),
                        false,
                        ProviderRowActions(
                            { connections++ },
                            {},
                            {},
                            {},
                            onDetectCapabilities = { calls += "capabilities" to it },
                            onContext = { calls += "context" to it },
                            onTestModel = { calls += "generation" to it },
                        ),
                        false,
                    )
                }
            }
        }
        listOf(ProviderProvisioningKind.USER_CONFIGURED, ProviderProvisioningKind.MANAGED_ACCOUNT).forEach { kind ->
            compose.runOnIdle {
                source.value = kind
                calls.clear()
            }
            compose.onNodeWithTag("provider-options-source").assertDoesNotExist()
            compose.onNodeWithTag("provider-context-source").assertDoesNotExist()
            compose.onNodeWithTag("provider-capabilities").assertDoesNotExist()
            compose.onNodeWithTag("provider-selected-model-a").assertDoesNotExist()
            compose.onNodeWithTag("provider-selected-model-embedding").assertDoesNotExist()
            compose.onNodeWithTag("provider-selected-model-b").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("provider-selected-model-custom").performScrollTo().assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(emptyList<Pair<String, String>>(), calls)
                assertEquals(0, connections)
            }
            compose.onNodeWithTag("provider-model-context-b").assertDoesNotExist()
            compose.onNodeWithTag("provider-selected-model-toggle-b").performScrollTo().performClick()
            compose.onNodeWithTag("provider-model-context-b").performScrollTo().performClick()
            compose.onNodeWithTag("provider-selected-model-toggle-custom").performScrollTo().performClick()
            compose.onNodeWithTag("provider-model-context-b").assertDoesNotExist()
            compose.onNodeWithTag("provider-model-test-custom").performScrollTo().performClick()
            compose.onNodeWithTag("provider-selected-model-toggle-b").performScrollTo().performClick()
            compose.onNodeWithTag("provider-model-probe-b").performScrollTo().performClick()
            compose.runOnIdle {
                assertEquals(listOf("context" to "b", "generation" to "custom", "capabilities" to "b"), calls)
                assertEquals(0, connections)
            }
        }
    }

    @Test fun capabilityDetectionDisablesEverySelectedModelAction() {
        val calls = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ProviderRow(
                        selectedRow("api", ProviderProvisioningKind.USER_CONFIGURED),
                        false,
                        ProviderRowActions(
                            {},
                            {},
                            {},
                            {},
                            onContext = { calls += it },
                            onTestModel = { calls += it },
                            onDetectCapabilities = { calls += it },
                        ),
                        false,
                        detectingCapabilities = true,
                    )
                }
            }
        }
        listOf("b", "custom").forEach { model ->
            compose.onNodeWithTag("provider-selected-model-toggle-$model").performScrollTo().performClick()
            listOf("context", "test", "probe").forEach { action ->
                compose
                    .onNodeWithTag("provider-model-$action-$model")
                    .performScrollTo()
                    .assertIsNotEnabled()
                    .performClick()
            }
        }
        compose.runOnIdle { assertEquals(emptyList<String>(), calls) }
    }

    @Test fun emptySelectionHasNoModelActionsDespiteConfiguredModelAndCatalog() {
        val source = androidx.compose.runtime.mutableStateOf(ProviderProvisioningKind.USER_CONFIGURED)
        compose.setContent {
            MaterialTheme {
                ProviderRow(
                    selectedRow(
                        "source",
                        source.value,
                    ).copy(modelSelection = ProviderModelSelection(emptyList(), configured = true)),
                    false,
                    ProviderRowActions(
                        { error("Not requested") },
                        {},
                        {},
                        {},
                        onContext = { error("No selected model") },
                        onTestModel = { error("No selected model") },
                        onDetectCapabilities = { error("No selected model") },
                    ),
                    false,
                )
            }
        }
        listOf(ProviderProvisioningKind.USER_CONFIGURED, ProviderProvisioningKind.MANAGED_ACCOUNT).forEach { kind ->
            compose.runOnIdle { source.value = kind }
            compose.onNodeWithTag("provider-selected-models-source").assertExists()
            compose.onNodeWithTag("provider-options-source").assertDoesNotExist()
            listOf("a", "b", "custom", "embedding").forEach { model ->
                compose.onNodeWithTag("provider-selected-model-$model").assertDoesNotExist()
                listOf("context", "test", "probe").forEach { action ->
                    compose.onNodeWithTag("provider-model-$action-$model").assertDoesNotExist()
                }
            }
        }
    }

    @Test fun identicalModelIdsRemainBoundToTheirProviderAcrossRecomposition() {
        val reversed = androidx.compose.runtime.mutableStateOf(false)
        val calls = mutableListOf<Triple<String, String, String>>()
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val providers = if (reversed.value) listOf("subscription", "api") else listOf("api", "subscription")
                    providers.forEach { id ->
                        val kind =
                            if (id ==
                                "api"
                            ) {
                                ProviderProvisioningKind.USER_CONFIGURED
                            } else {
                                ProviderProvisioningKind.MANAGED_ACCOUNT
                            }
                        ProviderSelectedModelList(
                            selectedRow(
                                id,
                                kind,
                            ).copy(modelSelection = ProviderModelSelection(listOf("b"), configured = true)),
                            false,
                            ProviderRowActions(
                                {},
                                {},
                                {},
                                {},
                                onContext = { calls += Triple(id, "context", it) },
                                onTestModel = { calls += Triple(id, "test", it) },
                                onDetectCapabilities = { calls += Triple(id, "probe", it) },
                            ),
                        )
                    }
                }
            }
        }
        listOf(false, true).forEach { reverse ->
            compose.runOnIdle {
                reversed.value = reverse
                calls.clear()
            }
            exerciseProviderScopedActions(calls)
        }
    }

    private fun exerciseProviderScopedActions(calls: List<Triple<String, String, String>>) {
        listOf("api", "subscription").forEach { id ->
            val actionVisible =
                compose
                    .onAllNodes(
                        hasTestTag("provider-model-context-b") and
                            hasAnyAncestor(hasTestTag("provider-selected-models-$id")),
                    ).fetchSemanticsNodes()
                    .isNotEmpty()
            if (!actionVisible) {
                compose
                    .onNode(
                        hasTestTag("provider-selected-model-toggle-b") and
                            hasAnyAncestor(hasTestTag("provider-selected-models-$id")),
                    ).performScrollTo()
                    .performClick()
            }
            listOf("context", "test", "probe").forEach { action ->
                compose
                    .onNode(
                        hasTestTag("provider-model-$action-b") and
                            hasAnyAncestor(hasTestTag("provider-selected-models-$id")),
                    ).performScrollTo()
                    .performClick()
            }
        }
        compose.runOnIdle {
            assertEquals(
                listOf("api", "subscription").flatMap { id ->
                    listOf("context", "test", "probe").map { Triple(id, it, "b") }
                },
                calls,
            )
        }
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
                    ProviderRowActions({}, {}, {}, {}),
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
                    .boundsInRoot
            val models =
                compose
                    .onNodeWithTag("provider-manage-models-$id")
                    .fetchSemanticsNode()
                    .boundsInRoot
            compose.onNodeWithTag("provider-test").assertDoesNotExist()
            compose.onNodeWithTag("provider-options-$id").assertDoesNotExist()
            compose.onNodeWithTag("provider-manage-account").assertIsDisplayed()
            compose.onNodeWithTag("provider-manage-models-$id").assertIsDisplayed()
            // The responsive action group uses separate touch targets, horizontally or wrapped.
            org.junit.Assert.assertTrue(login.left >= models.right || login.top >= models.bottom)
        }
    }

    @Test fun hiddenCurrentIsExplainedButNotReinsertedIntoAnEmptyPicker() {
        val row = row("api", ProviderProvisioningKind.USER_CONFIGURED, "a")
        compose.setContent {
            MaterialTheme {
                ComposerModelMenu(listOf(row).conversationModelSources(row.id, "a"), row.id, "a", true, onSave = {
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
                ComposerModelMenu(
                    listOf(
                        subscription,
                        local,
                    ).conversationModelSources(null, null),
                    null,
                    null,
                    true,
                    onSave = {
                        provider,
                        model,
                        _,
                        ->
                        selected =
                            provider to model
                        SessionModelSelectionResult.APPLIED
                    },
                )
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
                ComposerModelMenu(
                    listOf(
                        subscription,
                    ).conversationModelSources(null, null),
                    null,
                    null,
                    true,
                    onSave = {
                        _,
                        _,
                        _,
                        ->
                        error("Hidden")
                    },
                )
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
                ComposerModelMenu(
                    listOf(
                        row,
                    ).conversationModelSources(row.id, current.value),
                    row.id,
                    current.value,
                    true,
                    { _, model, reasoning ->
                        requested = model
                        if (succeeds) {
                            current.value = model
                            actualReasoning.value = reasoning
                            SessionModelSelectionResult.APPLIED
                        } else {
                            SessionModelSelectionResult.FAILED
                        }
                    },
                    reasoning = actualReasoning.value,
                )
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

    private fun selectedRow(
        id: String,
        kind: ProviderProvisioningKind,
    ): ProviderRowUi =
        row(id, kind, "a").copy(
            modelSelection = ProviderModelSelection(listOf("b", "custom", "embedding"), configured = true),
            modelMetadata =
                mapOf(
                    "embedding" to
                        com.helix.provider.api.ModelMetadata(
                            purposes = setOf(com.helix.provider.api.ModelPurpose.EMBEDDING),
                        ),
                ),
            accountState =
                com.helix.app.provider.ManagedAccountSnapshot(
                    com.helix.app.provider.ManagedAccountSnapshot.State.LOGGED_IN,
                    "12345678-1234-1234-1234-123456789abc",
                ),
        )

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
        provisioning = kind,
    )
}
