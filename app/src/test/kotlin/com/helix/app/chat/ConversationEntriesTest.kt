package com.helix.app.chat

import com.helix.core.model.TurnState
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationEntriesTest {
    @Test fun oldToolsAndMessageFreeTurnsStayBeforeTheNewestAnswer() {
        val state =
            ChatScreenState(
                sessions = emptyList(),
                openSessionId = "s",
                badge = null,
                activeTurn = null,
                pendingDisclosure = null,
                blockedReason = null,
                retryTargetTurnId = null,
                messages = listOf(MessageUi("a", "user", "first", "t1"), MessageUi("b", "assistant", "latest", "t3")),
                turns = listOf("t1", "t2", "t3").map { TurnUi(it, TurnState.COMPLETED, null, null, false) },
                toolTimeline = listOf(ToolTimelineRow("t2", "call", "read", "{}", "done", "ok", null)),
            )
        val rows = conversationEntries(state)
        assertEquals(listOf("t1", "t2", "t3"), rows.map { it.key })
        assertEquals("call", rows[1].tools.single().callId)
        assertEquals(
            "latest",
            rows
                .last()
                .messages
                .single()
                .content,
        )
    }
}
