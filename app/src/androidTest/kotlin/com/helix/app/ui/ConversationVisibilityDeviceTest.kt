package com.helix.app.ui

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import com.helix.app.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ConversationVisibilityDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun resumedConversationOwnsDisplayOnlyAndNavigationReleasesIt() {
        compose.resetDeterministicUiState()
        val chat = compose.container().chatService
        val original = chat.screen.value.openSessionId
        compose.waitUntil(10_000) { chat.conversationVisible }
        compose.navigateTo("settings")
        compose.waitUntil(10_000) { !chat.conversationVisible }
        assertEquals(original, chat.screen.value.openSessionId)
        compose.navigateTo("sessions")
        compose.waitUntil(10_000) { chat.conversationVisible }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertFalse(chat.conversationVisible)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(10_000) { chat.conversationVisible }
        assertTrue(chat.conversationVisible)
        chat.closeSession()
        compose.waitUntil(10_000) { chat.screen.value.openSessionId == null }
        assertTrue("An empty conversation page also owns its display lease", chat.conversationVisible)
    }
}
