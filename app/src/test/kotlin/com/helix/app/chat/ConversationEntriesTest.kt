package com.helix.app.chat

import com.helix.core.model.TurnState
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationEntriesTest {
    @Test fun laterUserAnswerStaysAfterTheAssistantQuestion() {
        val messages =
            listOf(
                MessageUi("q1", "user", "Start", "t"),
                MessageUi("a1", "assistant", "Choose an option", "t"),
                MessageUi("q2", "user", "Option A", "t"),
                MessageUi("a2", "assistant", "Done", "t"),
            )
        val entry = ConversationEntry("t", messages, emptyList(), emptyList())
        assertEquals(listOf("q1"), entry.initialMessages.map { it.id })
        assertEquals(listOf("a1", "q2", "a2"), entry.followingMessages.map { it.id })
        assertEquals(messages, entry.initialMessages + entry.followingMessages)
    }

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
        assertEquals(null, rows[0].forkMessageId(state))
        assertEquals("b", rows[2].forkMessageId(state))
        val running =
            state.copy(
                turns =
                    state.turns.map {
                        if (it.id == "t3") it.copy(state = TurnState.RECEIVING_MODEL) else it
                    },
            )
        assertEquals(null, rows[2].forkMessageId(running))
        val multiStep =
            rows[2].copy(
                messages =
                    listOf(
                        MessageUi("step", "assistant", "Working", "t3"),
                        MessageUi("final", "assistant", "Done", "t3"),
                    ),
            )
        assertEquals("final", multiStep.forkMessageId(state))
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
