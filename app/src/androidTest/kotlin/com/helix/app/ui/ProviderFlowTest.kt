package com.helix.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * HXA-028 provider UI flow (both variants): create → untested → NOT
 * chat-selectable → connection test against an unreachable endpoint fails at
 * phase 1 with a SAFE label (FR-LLM-004) → still not selectable → delete.
 *
 * The endpoint `https://127.0.0.1:9/v1` is the emulator's own loopback on a
 * closed port: the probe must fail fast at phase 1 (network & auth) without
 * any host-server dependency. Provider state is wiped per test so the single
 * row under test is unambiguous.
 */
@RunWith(AndroidJUnit4::class)
class ProviderFlowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val providerName = "UI 测试 Provider ${System.currentTimeMillis()}"

    @Before
    fun setUp() {
        composeRule.resetDeterministicUiState()
        deleteAllProviders(composeRule.container())
    }

    @Test
    fun untestedAndFailedProvidersAreNotChatSelectable() {
        // --- create from the Ollama template (keyless), re-point to https ---
        composeRule.navigateTo("settings")
        composeRule.onNodeWithTag("provider-add").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("provider-template-picker").assertIsDisplayed()
        composeRule.onNodeWithTag("provider-template-ollama").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("provider-form-dialog").assertIsDisplayed()

        composeRule.onNodeWithTag("provider-form-name").performTextClearance()
        composeRule.onNodeWithTag("provider-form-name").performTextInput(providerName)
        composeRule.onNodeWithTag("provider-form-endpoint").performTextClearance()
        composeRule.onNodeWithTag("provider-form-endpoint").performTextInput("https://127.0.0.1:9/v1")
        composeRule.onNodeWithTag("provider-form-model").performTextInput("probe-model")
        composeRule.onNodeWithTag("provider-form-save").performClick()
        composeRule.waitForIdle()

        // --- created: the dialog closed (save succeeded) and the row is 未测试 ---
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("provider-form-dialog").assertIsNotDisplayed()
        composeRule.onNodeWithText(providerName).assertIsDisplayed()
        composeRule.onNodeWithTag("provider-status-untested").assertIsDisplayed()
        composeRule.onNodeWithText("尚未通过连接测试", substring = true).assertIsDisplayed()

        // --- an untested provider must NOT appear in the new-session picker ---
        composeRule.navigateTo("sessions")
        composeRule.onNodeWithTag("chat-new-session").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("chat-new-session-dialog").assertIsDisplayed()
        composeRule.onNodeWithText(providerName).assertIsNotDisplayed()
        composeRule.onNodeWithTag("chat-new-session-cancel").performClick()

        // --- connection test against the unreachable endpoint: phase-1 failure ---
        composeRule.navigateTo("settings")
        composeRule.onNodeWithTag("provider-test").performClick()
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithTag("provider-status-failed").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("provider-status-failed").assertIsDisplayed()
        composeRule.onNodeWithText("失败阶段：网络与认证", substring = true).assertIsDisplayed()
        // The safe code label is shown (doc 02 section 13: never raw exceptions).
        composeRule.onNodeWithText("网络/TLS 连接失败", substring = true).assertIsDisplayed()

        // --- a FAILED provider is still not chat-selectable (only Passed is) ---
        composeRule.navigateTo("sessions")
        composeRule.onNodeWithTag("chat-new-session").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(providerName).assertIsNotDisplayed()
        composeRule.onNodeWithTag("chat-new-session-cancel").performClick()

        // --- cleanup: the UI delete removes the row (and its secret/binding) ---
        composeRule.navigateTo("settings")
        composeRule.onNodeWithTag("provider-delete").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(providerName).assertIsNotDisplayed()
        val rows =
            composeRule
                .container()
                .providerService.rows.value
        assertTrue(
            "the deleted provider must be gone from the persisted rows",
            rows.none { it.displayName == providerName },
        )
    }
}
