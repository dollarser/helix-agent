package com.helix.app.eval

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.helix.app.HelixApplication
import com.helix.app.ui.ChatScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.Properties

internal fun openProotConversation(
    compose: ComposeContentTestRule,
    app: HelixApplication,
    facts: Properties,
) {
    val container = app.appContainer
    val chat = container.chatService
    val session = facts.getProperty("session")
    chat.openSession(session)
    compose.waitUntil(15000) { chat.screen.value.openSessionId == session }
    compose.setContent {
        MaterialTheme { ChatScreen(chat, container.providerService, container.privacyDeletionService) }
    }
    compose.waitUntil(15000) {
        val screen = chat.screen.value
        screen.openSessionId == session && screen.toolTimeline.any { it.callId == facts.getProperty("call") }
    }
    assertEquals(session, chat.screen.value.openSessionId)
    assertTrue(
        chat.screen.value.toolTimeline
            .single { it.callId == facts.getProperty("call") }
            .prootRecoveryAvailable,
    )
    compose.waitUntil(15000) { !chat.screen.value.isSending }
    // A real history-browsing gesture disables tail following; semantic jumps alone do not.
    compose.onNodeWithTag("chat-timeline").performTouchInput { swipeDown() }
    val details = "tool-details-${facts.getProperty("call")}"
    compose.onNodeWithTag("chat-timeline").performScrollToNode(hasTestTag(details))
    compose.onNodeWithTag(details).performClick()
}

internal fun revealProotAction(
    compose: ComposeContentTestRule,
    tag: String,
) {
    if (compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty()) {
        compose.onNodeWithTag("chat-timeline").performScrollToNode(hasTestTag(tag))
    }
}

internal fun clickProotAction(
    compose: ComposeContentTestRule,
    tag: String,
) {
    revealProotAction(compose, tag)
    compose.onNodeWithTag(tag).performClick()
}
