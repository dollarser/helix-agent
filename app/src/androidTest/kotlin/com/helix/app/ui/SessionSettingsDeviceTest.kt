package com.helix.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.helix.app.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class SessionSettingsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun viewingSettingsKeepsDraftEphemeralUntilDurableConfigIsRequested() {
        compose.resetDeterministicUiState()
        val container = compose.container()
        val chat = container.chatService
        val id = requireNotNull(chat.screen.value.openSessionId)

        assertNull(container.storage.sessions.find(id))
        compose.onNodeWithTag("chat-conversation-details").performClick()
        compose.onNodeWithTag("session-settings-open").performClick()
        compose.onNodeWithTag("screen-session-settings").assertIsDisplayed()
        assertNull(container.storage.sessions.find(id))

        compose.onNodeWithTag("session-settings-materialize-permissions").performClick()
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            container.storage.sessions.find(id) != null &&
                container.storage.sessionRunControls.forSession(id) != null &&
                !chat.screen.value.isDraft
        }
        assertNotNull(container.storage.sessions.find(id))
        compose.onNodeWithTag("settings-perm-mode-APPROVAL_REQUIRED").assertIsDisplayed()

        compose.onNodeWithTag("navigate-back").performClick()
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag("chat-permission-menu").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("chat-permission-menu").assertIsDisplayed()
    }

    @Test
    fun configuringExpertMaterializesDraftAndPersistsSessionProfile() {
        compose.resetDeterministicUiState()
        val container = compose.container()
        val chat = container.chatService
        val id = requireNotNull(chat.screen.value.openSessionId)
        assertNull(container.storage.sessions.find(id))

        compose.onNodeWithTag("chat-conversation-details").performClick()
        compose.onNodeWithTag("session-settings-open").performClick()
        compose.onNodeWithTag("session-settings-expert").performClick()
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            container.storage.sessions.find(id) != null &&
                compose.onAllNodesWithTag("session-expert-dialog").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("session-expert-name").performTextInput("Reviewer")
        compose.onNodeWithTag("session-expert-instruction").performTextInput("Focus on correctness.")
        compose.onNodeWithTag("session-expert-save").performClick()

        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            container.storage.sessionExperts
                .forSession(id)
                ?.displayName == "Reviewer"
        }
        val profile = requireNotNull(container.storage.sessionExperts.forSession(id))
        assertEquals("Focus on correctness.", profile.instruction)
    }

    @Test
    fun composerAddShowsOnlyImplementedMessageAndSessionActions() {
        compose.resetDeterministicUiState()
        compose.onNodeWithTag("chat-add").performClick()
        listOf(
            "composer-add-camera",
            "composer-add-photo",
            "composer-add-file",
            "composer-add-reference",
            "composer-add-expert",
            "composer-add-skills",
            "composer-add-connectors",
            "composer-add-session-settings",
        ).forEach { compose.onNodeWithTag(it).assertIsDisplayed() }
    }
}
