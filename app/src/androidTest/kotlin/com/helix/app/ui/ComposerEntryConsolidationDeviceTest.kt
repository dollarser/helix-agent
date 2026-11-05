package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.helix.core.model.ProviderProvisioningKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ComposerEntryConsolidationDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun addContainsOneAttachmentEntryAndNoSessionOrVoiceSettings() {
        var files = 0
        var dismissals = 0
        var experts = 0
        var plugins = 0
        compose.setContent {
            MaterialTheme {
                ComposerAddSheet(
                    messageEnabled = true,
                    capabilityEnabled = true,
                    actions =
                        ComposerActions(
                            onFile = { files++ },
                            onExpert = { experts++ },
                            onConnectors = { plugins++ },
                            onVoice = {},
                            onSend = {},
                            onStop = {},
                        ),
                    onDismiss = { dismissals++ },
                )
            }
        }
        listOf("composer-add-photo", "composer-add-continuous-voice")
            .forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
        compose.onNodeWithTag("composer-add-skills").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("composer-add-expert").performScrollTo().performClick()
        compose.onNodeWithTag("composer-add-connectors").performScrollTo().performClick()
        compose.onNodeWithTag("composer-add-file").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, files)
            assertEquals(3, dismissals)
            assertEquals(1, experts)
            assertEquals(1, plugins)
        }
    }

    @Test fun bindingFailureKeepsItsMessageAndUsesTheExistingModelEntry() {
        compose.setContent {
            MaterialTheme {
                ConversationSection(
                    com.helix.app.chat.ChatScreenState(
                        emptyList(),
                        "fixture",
                        null,
                        emptyList(),
                        emptyList(),
                        null,
                        null,
                        null,
                        null,
                        modelBindingIssue = com.helix.app.provider.ProviderBindingIssue.SOURCE_DELETED,
                    ),
                    com.helix.core.agent.RunControlConfig(
                        com.helix.core.model.AgentMode.ACT,
                        false,
                        com.helix.core.agent.TurnBudgetBounds.DEFAULT,
                    ),
                    "retained draft",
                    {},
                    emptyList(),
                    ConversationIntents(
                        onSend = {},
                        onStop = {},
                        onDismissBlocked = {},
                        onApproveApproval = {},
                        onDenyApproval = {},
                        onStageAttachment = {},
                        onRemoveAttachment = {},
                        onSetMode = {},
                    ),
                )
            }
        }
        compose.onNodeWithTag("model-binding-issue").assertIsDisplayed()
        compose
            .onAllNodes(
                androidx.compose.ui.test
                    .hasAnyAncestor(
                        androidx.compose.ui.test
                            .hasTestTag("model-binding-issue"),
                    ) and
                    androidx.compose.ui.test
                        .hasClickAction(),
            ).assertCountEquals(0)
        compose.onNodeWithTag("chat-model-menu").performClick()
        compose.onNodeWithTag("chat-model-list").assertIsDisplayed()
    }

    @Test fun voiceModesRemainAvailableOnlyAfterTheVoiceEntryAndCancelDoesNotStartCapture() {
        val choices = mutableListOf<Boolean>()
        var dismissals = 0
        compose.setContent {
            MaterialTheme { VoiceInputModeSheet({ dismissals++ }) { choices += it } }
        }
        compose.onNodeWithTag("voice-input-modes-close").performClick()
        compose.runOnIdle {
            assertEquals(emptyList<Boolean>(), choices)
            assertEquals(1, dismissals)
        }
        compose.onNodeWithTag("voice-input-continuous").performClick()
        compose.onNodeWithTag("voice-input-single").performClick()
        compose.runOnIdle { assertEquals(listOf(true, false), choices) }
    }

    @Test fun modelPickerRetainsSourceConfigurationWithoutGeneralModelManagement() {
        val chosen = mutableListOf<ProviderProvisioningKind>()
        compose.setContent {
            MaterialTheme {
                ComposerModelMenu(
                    emptyList(),
                    null,
                    null,
                    true,
                    { _, _, _ -> error("No selectable model") },
                    sourceGroups = listOf(ProviderProvisioningKind.USER_CONFIGURED),
                    onConfigureSource = { chosen += it },
                )
            }
        }
        compose.onNodeWithTag("chat-model-menu").performClick()
        compose.onNodeWithText("管理对话模型").assertDoesNotExist()
        compose.onNodeWithText("Manage conversation models").assertDoesNotExist()
        compose.onNodeWithTag("chat-model-configure-USER_CONFIGURED").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(ProviderProvisioningKind.USER_CONFIGURED), chosen) }
    }
}
