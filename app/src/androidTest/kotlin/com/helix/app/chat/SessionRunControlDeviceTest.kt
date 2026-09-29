package com.helix.app.chat

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.helix.app.MainActivity
import com.helix.app.ui.ASYNC_UI_TIMEOUT_MILLIS
import com.helix.app.ui.container
import com.helix.app.ui.resetDeterministicUiState
import com.helix.core.model.AgentMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

class SessionRunControlDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun sessionRunControlIsolatedAndDefaultsOnlySeedNewSessions() =
        runBlocking {
            compose.resetDeterministicUiState()
            val container = compose.container()
            val chat = container.chatService
            val defaults = container.runControlStore
            val original = defaults.current

            try {
                defaults.setMode(AgentMode.CHAT)

                val first = requireNotNull(chat.screen.value.openSessionId)
                org.junit.Assert.assertFalse(chat.setModeFromComposer("not-the-open-session", AgentMode.GOAL))
                org.junit.Assert.assertTrue(chat.setModeFromComposer(first, AgentMode.PLAN))
                compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) { chat.runControl.value.mode == AgentMode.PLAN }
                assertEquals(first, chat.materializeDraftSession(first))

                chat.newSessionDraft()
                compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
                    chat.screen.value.isDraft && chat.screen.value.openSessionId != first
                }
                val second = requireNotNull(chat.screen.value.openSessionId)
                assertEquals(AgentMode.ACT, chat.runControl.value.mode)
                chat.setMode(AgentMode.ACT)
                assertEquals(second, chat.materializeDraftSession(second))

                chat.openSession(first)
                compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
                    chat.screen.value.openSessionId == first && chat.runControl.value.mode == AgentMode.PLAN
                }

                chat.openSession(second)
                compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
                    chat.screen.value.openSessionId == second && chat.runControl.value.mode == AgentMode.ACT
                }

                defaults.setMode(AgentMode.GOAL)
                chat.openSession(first)
                compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
                    chat.screen.value.openSessionId == first && chat.runControl.value.mode == AgentMode.PLAN
                }

                chat.newSessionDraft()
                compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
                    chat.screen.value.isDraft && chat.screen.value.openSessionId != first &&
                        chat.screen.value.openSessionId != second
                }
                assertNotEquals(first, chat.screen.value.openSessionId)
                assertEquals(AgentMode.ACT, chat.runControl.value.mode)
            } finally {
                defaults.setMode(original.mode)
                defaults.setReasoning(original.reasoning)
                defaults.setChatToolsEnabled(original.chatToolsEnabled)
                defaults.setBudgets(original.budgets)
                defaults.setGoalBudgets(original.goalBudgets)
            }
        }
}
