package com.helix.extensions.chatreply.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatConversationTest {
    @Test
    fun latestIncomingAndIdentityIgnoreMessageChanges() {
        val first =
            ChatConversation(
                "example.chat",
                4,
                "A",
                listOf(ChatMessage(MessageSide.ME, "Hi"), ChatMessage(MessageSide.OTHER, "Hello")),
            )
        val updated = first.copy(messages = first.messages + ChatMessage(MessageSide.OTHER, "Are you free?"))
        assertEquals("Are you free?", updated.latestIncoming?.text)
        assertTrue(first.sameConversation(updated))
        assertFalse(first.signature() == updated.signature())
    }

    @Test
    fun refusesUnboundedOrBlankMessages() {
        val blankRejected =
            runCatching {
                ChatConversation("example.chat", 1, null, listOf(ChatMessage(MessageSide.OTHER, " ")))
            }
        assertTrue(blankRejected.isFailure)
        val tooManyRejected =
            runCatching {
                ChatConversation(
                    "example.chat",
                    1,
                    null,
                    List(ChatConversation.MAX_MESSAGES + 1) { ChatMessage(MessageSide.OTHER, "hi") },
                )
            }
        assertTrue(tooManyRejected.isFailure)
    }
}
