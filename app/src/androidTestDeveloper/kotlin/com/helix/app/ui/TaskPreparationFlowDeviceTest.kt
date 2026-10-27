package com.helix.app.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
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

    @Test fun phoneEntryKeepsExistingDraftAndDoesNotSendOrEnableThePlugin() {
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
        compose.onNodeWithTag("mobile-use-dialog").assertIsDisplayed()
        compose.onNodeWithTag("connector-session-panel").assertDoesNotExist()
        Espresso.pressBack()
        compose.onNodeWithTag("chat-input").assertTextContains("保留我的要求", substring = true)
        compose
            .onNodeWithTag("chat-input")
            .assertTextContains(compose.activity.getString(R.string.chat_task_document), substring = true)
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

    @Test fun missingPhoneBackendExplainsAndReturnsToSameDialogAfterRecreation() {
        compose.resetDeterministicUiState()
        check(compose.activity.packageName.endsWith(".asrvalidation"))
        savePhoneSettingsViaUi()
        compose.navigateTo("sessions")
        compose.onNodeWithTag("chat-input").performTextInput("PHONE_NAV_DRAFT")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("phone-task-prepare").performScrollTo().performClick()
        awaitPhoneDialog()
        compose.onNodeWithText(compose.activity.getString(R.string.plugin_setup_mobile_backend)).assertIsDisplayed()
        repeat(2) { index ->
            compose.onNodeWithTag("mobile-use-dialog-confirm").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("screen-permissions").fetchSemanticsNodes().isNotEmpty()
            }
            if (index == 1) {
                compose.runOnUiThread { compose.activity.recreate() }
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithTag("screen-permissions").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("navigate-back").performClick()
            } else {
                Espresso.pressBack()
            }
            awaitPhoneDialog()
        }
        Espresso.pressBack()
        compose.onNodeWithTag("chat-input").assertTextContains("PHONE_NAV_DRAFT")
        val container = compose.container()
        val session = requireNotNull(container.chatService.screen.value.openSessionId)
        assertEquals(
            false,
            container.pluginService
                .sessionRows(session, "bundled-plugin:mobile-use")
                .single()
                .selected,
        )
    }

    @Test fun phoneSettingsSaveReturnsToSourceWithoutSelectingThePlugin() {
        compose.resetDeterministicUiState()
        check(compose.activity.packageName.endsWith(".asrvalidation"))
        savePhoneSettingsViaUi()
    }

    private fun savePhoneSettingsViaUi() {
        compose.navigateTo("extensions")
        compose.onNodeWithTag("plugin-manage-bundled-plugin:mobile-use").performScrollTo().performClick()
        compose.onNodeWithTag("mobile-use-plugin-settings").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose
                .onAllNodesWithText(compose.activity.getString(R.string.tasks_commands_loading))
                .fetchSemanticsNodes()
                .isEmpty()
        }
        if (compose.onAllNodesWithTag("mobile-use-save-settings").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("mobile-use-advanced").performClick()
        }
        compose.onNodeWithTag("mobile-use-save-settings").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("mobile-use-global-settings").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("screen-extensions").assertIsDisplayed()
    }

    private fun awaitPhoneDialog() {
        compose.waitUntil(10_000) {
            compose
                .onAllNodesWithTag("mobile-use-dialog-confirm")
                .fetchSemanticsNodes()
                .singleOrNull()
                ?.config
                ?.contains(SemanticsProperties.Disabled) == false
        }
    }
}
