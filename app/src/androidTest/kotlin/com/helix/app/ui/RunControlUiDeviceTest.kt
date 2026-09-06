package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.MainActivity
import com.helix.app.runcontrol.RunControlConfig
import com.helix.app.runcontrol.TurnBudgetBounds
import com.helix.core.model.AgentMode
import com.helix.core.model.TurnBudgets
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RunControlModeUiDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun everyModeHasAnExplainableEntryAndChatRequiresExplicitToolOptIn() {
        var config = RunControlConfig(AgentMode.CHAT, false, TurnBudgetBounds.DEFAULT)
        composeRule.setContent {
            MaterialTheme {
                ModeControlSection(
                    config = config,
                    turnActive = false,
                    intents =
                        ConversationIntents(
                            onBack = {},
                            onSend = {},
                            onStop = {},
                            onRetry = {},
                            onDismissBlocked = {},
                            onApproveApproval = {},
                            onDenyApproval = {},
                            onStageAttachment = {},
                            onRemoveAttachment = {},
                            onBindProvider = {},
                            onSetMode = { config = config.copy(mode = it) },
                            onSetChatTools = { config = config.copy(chatToolsEnabled = it) },
                        ),
                )
            }
        }

        AgentMode.entries.forEach { mode ->
            composeRule.onNodeWithTag("chat-mode-${mode.name.lowercase()}").assertExists()
        }
        composeRule.onNodeWithTag("chat-mode-explanation").assertIsDisplayed()
        composeRule.onNodeWithTag("chat-tools-toggle").assertIsDisplayed()
        composeRule.onNodeWithTag("chat-budget-summary").assertIsDisplayed()
    }
}

@RunWith(AndroidJUnit4::class)
class RunControlSettingsUiDeviceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun boundedBudgetsPersistAcrossActivityRecreation() {
        composeRule.resetDeterministicUiState()
        composeRule.navigateTo("settings")
        composeRule.onNodeWithTag("settings-turn-budgets").performScrollTo().assertIsDisplayed()
        replace("budget-steps", "7")
        replace("budget-calls", "6")
        replace("budget-input", "12000")
        replace("budget-output", "2000")
        replace("budget-total", "14000")
        composeRule.onNodeWithTag("budget-save").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.runOnUiThread { composeRule.activity.recreate() }
        composeRule.waitForIdle()
        composeRule.navigateTo("settings")
        val expected = TurnBudgets(7, 6, 12_000, 2_000, 14_000)
        check(
            composeRule
                .container()
                .runControlStore.current.budgets == expected,
        )
    }

    private fun replace(
        tag: String,
        text: String,
    ) {
        composeRule.onNodeWithTag(tag).performScrollTo()
        composeRule.onNodeWithTag(tag).performTextClearance()
        composeRule.onNodeWithTag(tag).performTextInput(text)
    }
}
