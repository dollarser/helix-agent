package com.helix.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import com.helix.app.MainActivity
import com.helix.app.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Real navigation and persistence with no model calls, microphone or permission grants. */
class TaskPreparationFlowDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun taskExamplesKeepExistingDraftAndDoNotSendOrEnableThePlugin() {
        compose.resetDeterministicUiState()
        val plugins = compose.container().pluginService
        val enabled = plugins.list().single { it.native?.pluginId == "mobile-use" }.enabled
        compose.onNodeWithTag("chat-input").performTextInput("保留我的要求")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("starter-prompt-${R.string.chat_task_document}").performScrollTo().performClick()
        compose.onNodeWithTag("chat-input").assertTextContains("保留我的要求", substring = true)
        compose
            .onNodeWithTag("chat-input")
            .assertTextContains(compose.activity.getString(R.string.chat_task_document), substring = true)
        compose.onNodeWithTag("phone-task-prepare").performScrollTo().performClick()
        compose.onNodeWithTag("phone-task-prepare-sheet").assertIsDisplayed()
        compose.onNodeWithTag("phone-task-example").performScrollTo().performClick()
        compose.onNodeWithTag("chat-input").assertTextContains("保留我的要求", substring = true)
        compose
            .onNodeWithTag("chat-input")
            .assertTextContains(compose.activity.getString(R.string.phone_task_example), substring = true)
        compose.onNodeWithTag("phone-task-prepare-sheet").assertDoesNotExist()
        assertEquals(enabled, plugins.list().single { it.native?.pluginId == "mobile-use" }.enabled)
        assertEquals(
            true,
            compose
                .container()
                .chatService.screen.value.messages
                .isEmpty(),
        )
    }

    @Test fun systemVoiceHelpCanBeOpenedClosedAndReopenedFromSettings() {
        compose.resetDeterministicUiState()
        compose.navigateTo("settings")
        repeat(2) {
            compose.onNodeWithText(compose.activity.getString(R.string.system_voice_title)).performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("system-voice-sheet").fetchSemanticsNodes().isNotEmpty()
            }
            compose
                .onNodeWithText(compose.activity.getString(R.string.system_voice_privacy))
                .performScrollTo()
                .assertIsDisplayed()
            compose.onNodeWithTag("system-voice-close").performClick()
            compose.onNodeWithTag("screen-settings").assertIsDisplayed()
        }
    }
}
