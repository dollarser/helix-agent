package com.helix.app.eval

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
    chat.closeSession()
    compose.waitUntil(15000) { chat.screen.value.openSessionId == null }
    compose.setContent {
        MaterialTheme { ChatScreen(chat, container.providerService, container.privacyDeletionService) }
    }
    val title =
        container.storage.sessions
            .resolve(session)
            .title
    compose.onNode(hasScrollAction()).performScrollToNode(hasText(title))
    compose.onNodeWithText(title).performClick()
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
    assertTrue(!chat.screen.value.isSending)
}

internal fun revealProotAction(
    compose: ComposeContentTestRule,
    tag: String,
) {
    if (compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty()) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag(tag))
    }
}

internal fun clickProotAction(
    compose: ComposeContentTestRule,
    tag: String,
) {
    revealProotAction(compose, tag)
    compose.onNodeWithTag(tag).performClick()
}
