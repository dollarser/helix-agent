package com.helix.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

        compose.onNodeWithTag("session-settings-materialize-permissions").performScrollTo().performClick()
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            container.storage.sessions.find(id) != null &&
                container.storage.sessionRunControls.forSession(id) != null &&
                !chat.screen.value.isDraft
        }
        assertNotNull(container.storage.sessions.find(id))
        compose.onNodeWithTag("settings-perm-mode-APPROVAL_REQUIRED").performScrollTo().assertIsDisplayed()

        compose.onNodeWithTag("navigate-back").performClick()
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag("chat-permission-menu").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("chat-permission-menu").assertIsDisplayed()
        val reference = requireNotNull(chat.screen.value.directoryRef)
        compose.onNodeWithTag("chat-conversation-details").performClick()
        compose.onNodeWithText(reference).assertDoesNotExist()
        compose
            .onNodeWithText(
                compose.activity.getString(com.helix.app.R.string.workspace_location_details),
            ).performClick()
        compose.onNodeWithText(reference).assertIsDisplayed()
        compose
            .onNodeWithText(
                compose.activity.getString(com.helix.app.R.string.workspace_copy_location),
            ).assertExists()
    }

    @Test fun composerPresetChangesOnlyThisSessionAndNotTheNextSessionDefault() {
        compose.resetDeterministicUiState()
        val container = compose.container()
        val chat = container.chatService
        val edit = container.sessionPermissionEdit
        val default = edit.appDefault()
        val id = requireNotNull(chat.screen.value.openSessionId)
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag("chat-permission-menu").fetchSemanticsNodes().any {
                !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
            }
        }
        assertNull(container.storage.sessions.find(id))
        compose.onNodeWithTag("chat-permission-menu").performClick()
        assertNull(container.storage.sessions.find(id))
        compose.onNodeWithTag("chat-permission-FULL_ACCESS").performClick()
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            edit.activeConfigFor(id)?.mode == com.helix.core.model.SessionPermissionMode.FULL_ACCESS
        }
        assertEquals(default, edit.appDefault())
        chat.newSessionDraft()
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) { chat.screen.value.openSessionId != id }
        val next = requireNotNull(chat.screen.value.openSessionId)
        assertEquals(default.mode, (edit.activeConfigFor(next) ?: edit.appDefault()).mode)
        assertEquals(com.helix.core.model.SessionPermissionMode.FULL_ACCESS, edit.activeConfigFor(id)?.mode)
    }

    @Test
    fun expertManagementSavesLibraryBeforeExplicitSessionSelection() {
        compose.resetDeterministicUiState()
        val container = compose.container()
        val chat = container.chatService
        val expertName = "Reviewer ${java.util.UUID.randomUUID()}"
        val id = requireNotNull(chat.screen.value.openSessionId)
        assertNull(container.storage.sessions.find(id))

        compose.onNodeWithTag("chat-conversation-details").performClick()
        compose.onNodeWithTag("session-settings-open").performClick()
        compose.onNodeWithTag("session-settings-expert").performScrollTo().performClick()
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            container.storage.sessions.find(id) != null &&
                compose.onAllNodesWithTag("session-expert-dialog").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("session-expert-name").assertDoesNotExist()
        clickExpertAction("expert-manage")
        clickExpertAction("expert-manage")
        compose.onNodeWithTag("session-expert-name").performScrollTo().performTextInput(expertName)
        compose.onNodeWithTag("session-expert-instruction").performScrollTo().performTextInput("Focus on correctness.")
        androidx.test.espresso.Espresso
            .closeSoftKeyboard()
        compose.onNodeWithTag("session-expert-save").performClick()
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            container.storage.experts
                .list()
                .any { it.displayName == expertName }
        }
        assertNull(container.storage.sessionExperts.forSession(id))
        val saved =
            container.storage.experts
                .list()
                .single { it.displayName == expertName }
        androidx.test.espresso.Espresso
            .pressBack()
        clickExpertAction("expert-row-${saved.id}")

        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            container.storage.sessionExperts
                .forSession(id)
                ?.displayName == expertName
        }
        val profile = requireNotNull(container.storage.sessionExperts.forSession(id))
        assertEquals("Focus on correctness.", profile.instruction)
        assertLibraryEditRequiresReselection(chat, container.storage, id, saved)
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
            "composer-add-connectors",
            "composer-add-skills",
        ).forEach { compose.onNodeWithTag(it).performScrollTo().assertIsDisplayed() }
        compose.onNodeWithTag("composer-add-session-settings").assertDoesNotExist()
    }

    private fun assertLibraryEditRequiresReselection(
        chat: com.helix.app.chat.ChatService,
        storage: com.helix.core.storage.HelixStorage,
        id: String,
        saved: com.helix.core.storage.repository.ExpertProfile,
    ) {
        kotlinx.coroutines.runBlocking {
            check(chat.saveExpert(saved.id, saved.displayName, "Updated library instruction.").await())
        }
        assertEquals(
            "Focus on correctness.",
            storage.sessionExperts
                .forSession(id)
                ?.instruction,
        )
        compose.onNodeWithTag("session-settings-expert").performScrollTo().performClick()
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag("expert-row-${saved.id}").fetchSemanticsNodes().any {
                it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Selected)
            }
        }
        compose
            .onNodeWithTag("expert-row-${saved.id}")
            .performScrollTo()
            .assertIsNotSelected()
            .performClick()
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            storage.sessionExperts
                .forSession(id)
                ?.instruction == "Updated library instruction."
        }
    }

    private fun clickExpertAction(tag: String) {
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().any {
                !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
            }
        }
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }
}
