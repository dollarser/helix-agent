package com.helix.app.chat

import com.helix.core.model.TurnState
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationEntriesTest {
    @Test fun callsStayBetweenTheirOriginalMessagesDespiteReverseSettlementAndApproval() {
        val messages =
            listOf(
                MessageUi("u", "user", "start", "t", 1),
                MessageUi("before", "assistant", "checking", "t", 2),
                MessageUi("between", "assistant", "now write", "t", 4),
                MessageUi("answer", "assistant", "done", "t", 6),
            )
        val first =
            ToolTimelineRow("t", "first", "read", "{}", "running", null, null, position = ToolTimelinePosition(3, 0))
        val sibling = first.copy(callId = "sibling", position = ToolTimelinePosition(3, 1))
        val second = first.copy(callId = "second", position = ToolTimelinePosition(5, 0))
        val entry = ConversationEntry("t", messages, listOf(second, sibling, first), emptyList())
        val expected = listOf("u", "before", "tool-first", "tool-sibling", "between", "tool-second", "answer")
        assertEquals(expected, entry.timeline.map { it.key })
        assertEquals(
            expected,
            entry
                .copy(
                    tools = listOf(sibling.copy(stateLabel = "denied"), second, first.copy(stateLabel = "completed")),
                ).timeline
                .map {
                    it.key
                },
        )
        assertEquals(
            listOf(
                "u",
                "before",
                "between",
                "answer",
                "tool-unlocated",
            ),
            entry.copy(tools = listOf(first.copy(callId = "unlocated", position = null))).timeline.map {
                it.key
            },
        )
    }

    @Test fun laterUserAnswerStaysAfterTheAssistantQuestion() {
        val messages =
            listOf(
                MessageUi("q1", "user", "Start", "t"),
                MessageUi("a1", "assistant", "Choose an option", "t"),
                MessageUi("q2", "user", "Option A", "t"),
                MessageUi("a2", "assistant", "Done", "t"),
            )
        val entry = ConversationEntry("t", messages, emptyList(), emptyList())
        assertEquals(listOf("q1", "a1", "q2", "a2"), entry.timeline.map { it.key })
        assertEquals(messages, entry.timeline.map { (it as ConversationContent.Message).value })
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
