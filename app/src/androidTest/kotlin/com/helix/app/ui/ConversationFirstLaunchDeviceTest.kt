package com.helix.app.ui

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import com.helix.app.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ConversationFirstLaunchDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun durableSessionIsRestoredInsteadOfLandingOnHistory() =
        runBlocking {
            compose.resetDeterministicUiState()
            val container = compose.container()
            val chat = container.chatService

            compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) { chat.screen.value.isDraft }
            val sessionId = requireNotNull(chat.screen.value.openSessionId)
            assertTrue(chat.saveDraftForGoal("Conversation-first persisted fixture"))
            compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
                container.storage.sessions.find(sessionId) != null && !chat.screen.value.isDraft
            }

            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            chat.closeSession()
            compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) { chat.screen.value.openSessionId == null }
            compose.activityRule.scenario.recreate()
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitForIdle()
            compose.dismissFirstLaunchIfNeeded()

            compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) { chat.screen.value.openSessionId == sessionId }
            assertFalse(chat.screen.value.isDraft)
            compose.onNodeWithTag("chat-input").assertExists()
            Unit
        }

    @Test
    fun explicitNewConversationRestoresAsFreshEphemeralDraft() {
        compose.resetDeterministicUiState()
        val container = compose.container()
        val chat = container.chatService

        val previousDraftId = requireNotNull(chat.screen.value.openSessionId)
        compose.onNodeWithTag("chat-new-session").performClick()
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            chat.screen.value.isDraft && chat.screen.value.openSessionId != previousDraftId
        }
        val abandonedDraftId = requireNotNull(chat.screen.value.openSessionId)
        assertTrue(container.storage.sessions.find(abandonedDraftId) == null)

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        chat.closeSession()
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) { chat.screen.value.openSessionId == null }
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        compose.dismissFirstLaunchIfNeeded()

        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) { chat.screen.value.isDraft }
        val restoredDraftId = requireNotNull(chat.screen.value.openSessionId)
        assertNotEquals(abandonedDraftId, restoredDraftId)
        assertTrue(container.storage.sessions.find(restoredDraftId) == null)
        compose.onNodeWithTag("chat-input").assertExists()
    }
}
