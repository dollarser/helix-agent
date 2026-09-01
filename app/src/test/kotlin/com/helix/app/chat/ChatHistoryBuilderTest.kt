package com.helix.app.chat

import com.helix.core.model.ModelRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHistoryBuilderTest {
    private fun textRow(
        turnId: String?,
        role: String,
        content: String?,
    ) = ChatHistoryBuilder.PersistedRow(turnId, role, ChatHistoryBuilder.KIND_TEXT, content)

    // --------------------------------------------------------------- text rows (M2 shape)

    @Test
    fun mapsUserAndAssistantRowsInPersistedOrder() {
        val messages =
            ChatHistoryBuilder.toModelMessages(
                listOf(
                    textRow("t1", "USER", "你好"),
                    textRow("t1", "ASSISTANT", "你好！"),
                    textRow("t2", "USER", "帮我总结"),
                ),
            )
        assertEquals(3, messages.size)
        assertEquals(ModelRole.USER, messages[0].role)
        assertEquals("你好", messages[0].text)
        assertEquals(ModelRole.ASSISTANT, messages[1].role)
        assertEquals(ModelRole.USER, messages[2].role)
    }

    @Test
    fun blankAndMissingContentRowsAreSkipped() {
        val messages =
            ChatHistoryBuilder.toModelMessages(
                listOf(
                    textRow("t1", "USER", "问题"),
                    textRow("t1", "ASSISTANT", null), // stopped turn with no output
                    textRow("t1", "ASSISTANT", "   "),
                    textRow("t2", "USER", "再来一次"),
                ),
            )
        assertEquals(2, messages.size)
        assertEquals("问题", messages[0].text)
        assertEquals("再来一次", messages[1].text)
    }

    @Test
    fun unknownRolesAndKindsAreSkippedSoTheHistoryNeverInventsContext() {
        val messages =
            ChatHistoryBuilder.toModelMessages(
                listOf(
                    textRow("t1", "USER", "hi"),
                    ChatHistoryBuilder.PersistedRow("t1", "SYSTEM", ChatHistoryBuilder.KIND_TEXT, "a system row"),
                    // A TOOL role without the tool-result kind is not a model-visible message.
                    textRow("t1", "TOOL", "raw tool text"),
                    // A TOOL_CALLS kind on a USER row is malformed: skipped.
                    ChatHistoryBuilder.PersistedRow("t1", "USER", ChatHistoryBuilder.KIND_TOOL_CALLS, "[]"),
                ),
            )
        assertEquals(1, messages.size)
        assertTrue(messages[0].text == "hi")
    }

    // ----------------------------------------------------- HXA-037: tool-call step rows

    @Test
    fun anAssistantToolCallStepMapsToAnAssistantMessageWithItsCallsInOrder() {
        val content =
            """[{"id":"c1","name":"fs.read","arguments":"{\"path\":\"a.txt\"}"},""" +
                """{"id":"c2","name":"time.now","arguments":"{}"}]"""
        val messages =
            ChatHistoryBuilder.toModelMessages(
                listOf(
                    textRow("t1", "USER", "读文件"),
                    ChatHistoryBuilder.PersistedRow("t1", "ASSISTANT", ChatHistoryBuilder.KIND_TOOL_CALLS, content),
                ),
            )
        assertEquals(2, messages.size)
        val step = messages[1]
        assertEquals(ModelRole.ASSISTANT, step.role)
        assertEquals("", step.text)
        assertEquals(2, step.toolCalls.size)
        assertEquals("c1", step.toolCalls[0].id.value)
        assertEquals("fs.read", step.toolCalls[0].name.value)
        assertEquals("""{"path":"a.txt"}""", step.toolCalls[0].argumentsJson)
        assertEquals("c2", step.toolCalls[1].id.value)
    }

    @Test
    fun toolResultRowsMapToToolMessagesKeyedByCallId() {
        val messages =
            ChatHistoryBuilder.toModelMessages(
                listOf(
                    ChatHistoryBuilder.PersistedRow(
                        "t1",
                        "TOOL",
                        ChatHistoryBuilder.KIND_TOOL_RESULT,
                        """{"id":"c1","tool":"fs.read","status":"SUCCEEDED","summary":"42 bytes read"}""",
                    ),
                    ChatHistoryBuilder.PersistedRow(
                        "t1",
                        "TOOL",
                        ChatHistoryBuilder.KIND_TOOL_RESULT,
                        """{"id":"c2","tool":"time.now","status":"CANCELLED","summary":""}""",
                    ),
                ),
            )
        assertEquals(2, messages.size)
        assertEquals(ModelRole.TOOL, messages[0].role)
        assertEquals("c1", messages[0].toolCallId!!.value)
        assertEquals("fs.read", messages[0].toolName!!.value)
        assertEquals("[SUCCEEDED] 42 bytes read", messages[0].text)
        assertEquals("c2", messages[1].toolCallId!!.value)
        assertEquals("[CANCELLED]", messages[1].text)
    }

    @Test
    fun aFullToolRoundTripKeepsTheModelVisibleSequence() {
        // USER, ASSISTANT(tool calls), TOOL, TOOL — the exact shape the vendor
        // protocols expect, in call sequence, rebuilt purely from persisted rows
        // (model-visible ⇔ persisted).
        val rows =
            listOf(
                textRow("t1", "USER", "q"),
                ChatHistoryBuilder.PersistedRow(
                    "t1",
                    "ASSISTANT",
                    ChatHistoryBuilder.KIND_TOOL_CALLS,
                    """[{"id":"c1","name":"a","arguments":"{}"},{"id":"c2","name":"b","arguments":"{}"}]""",
                ),
                ChatHistoryBuilder.PersistedRow(
                    "t1",
                    "TOOL",
                    ChatHistoryBuilder.KIND_TOOL_RESULT,
                    """{"id":"c1","tool":"a","status":"SUCCEEDED","summary":"ok"}""",
                ),
                ChatHistoryBuilder.PersistedRow(
                    "t1",
                    "TOOL",
                    ChatHistoryBuilder.KIND_TOOL_RESULT,
                    """{"id":"c2","tool":"b","status":"SUCCEEDED","summary":"ok"}""",
                ),
            )
        val messages = ChatHistoryBuilder.toModelMessagesStrict(rows)
        assertEquals(
            listOf(ModelRole.USER, ModelRole.ASSISTANT, ModelRole.TOOL, ModelRole.TOOL),
            messages.map { it.role },
        )
        assertEquals(listOf("c1", "c2"), messages.drop(2).map { it.toolCallId!!.value })
    }

    @Test
    fun malformedToolRowsAreSkippedLenientlyButThrowStrictly() {
        val badStep = ChatHistoryBuilder.PersistedRow("t1", "ASSISTANT", ChatHistoryBuilder.KIND_TOOL_CALLS, "not-json")
        val badResult =
            ChatHistoryBuilder.PersistedRow(
                "t1",
                "TOOL",
                ChatHistoryBuilder.KIND_TOOL_RESULT,
                """{"tool":"a"}""",
            )
        // Lenient: the row is skipped, the rest of the history survives.
        val lenient =
            ChatHistoryBuilder.toModelMessages(
                listOf(textRow("t1", "USER", "q"), badStep, badResult, textRow("t2", "USER", "again")),
            )
        assertEquals(2, lenient.size)
        assertEquals("q", lenient[0].text)
        assertEquals("again", lenient[1].text)
        // Strict: the model-bound request fails closed on the corrupted row.
        assertThrows(IllegalArgumentException::class.java) {
            ChatHistoryBuilder.toModelMessagesStrict(listOf(textRow("t1", "USER", "q"), badStep))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChatHistoryBuilder.toModelMessagesStrict(listOf(badResult))
        }
    }

    @Test
    fun anEmptyToolCallArrayIsNotAStep() {
        val row = ChatHistoryBuilder.PersistedRow("t1", "ASSISTANT", ChatHistoryBuilder.KIND_TOOL_CALLS, "[]")
        assertNull(ChatHistoryBuilder.toModelMessages(listOf(row)).singleOrNull())
        assertThrows(IllegalArgumentException::class.java) {
            ChatHistoryBuilder.toModelMessagesStrict(listOf(row))
        }
    }

    // ------------------------------------------------------------------- rowsForTurn

    @Test
    fun freshSendKeepsEveryRowInOrder() {
        val rows =
            listOf(
                textRow("t1", "USER", "a"),
                textRow("t1", "ASSISTANT", "b"),
                textRow("t2", "USER", "c"),
            )
        assertEquals(rows, ChatHistoryBuilder.rowsForTurn(rows, null))
    }

    @Test
    fun retryExcludesTheRetriedTurnsRowsAndReappendsItsUserMessageLast() {
        val rows =
            listOf(
                textRow("t1", "USER", "a"),
                textRow("t1", "ASSISTANT", "b"),
                textRow("t2", "USER", "c"),
                // t2 failed and left an assistant row
                textRow("t2", "ASSISTANT", "partial"),
                textRow("t3", "USER", "d"),
                textRow("t3", "ASSISTANT", "e"),
            )
        val history = ChatHistoryBuilder.toModelMessages(ChatHistoryBuilder.rowsForTurn(rows, "t2"))
        // a, b, d, e (the t2 rows are out) + the retried user message "c" last.
        assertEquals(listOf("a", "b", "d", "e", "c"), history.map { it.text })
        assertEquals(ModelRole.USER, history.last().role)
    }

    @Test
    fun retryOfTheNewestTurnStillEndsWithUser() {
        val rows =
            listOf(
                textRow("t1", "USER", "a"),
                textRow("t1", "ASSISTANT", "b"),
                textRow("t2", "USER", "c"),
            )
        val history = ChatHistoryBuilder.toModelMessages(ChatHistoryBuilder.rowsForTurn(rows, "t2"))
        assertEquals(listOf("a", "b", "c"), history.map { it.text })
        assertEquals(ModelRole.USER, history.last().role)
    }

    @Test
    fun retryOfACorruptedTurnWithoutAUserRowDegradesToTheFilteredHistory() {
        // t2 has only an assistant row (its user row is missing/corrupt): the
        // filter drops both t2 rows and re-appends nothing; the caller's
        // end-with-USER guard then fails closed on this result.
        val rows =
            listOf(
                textRow("t1", "USER", "a"),
                textRow("t1", "ASSISTANT", "b"),
                textRow("t2", "ASSISTANT", "orphan"),
            )
        val history = ChatHistoryBuilder.toModelMessages(ChatHistoryBuilder.rowsForTurn(rows, "t2"))
        assertEquals(listOf("a", "b"), history.map { it.text })
        assertEquals(ModelRole.ASSISTANT, history.last().role)
    }

    @Test
    fun aRetryAfterAToolTurnDropsTheWholeToolExchangeButKeepsEarlierRounds() {
        // The retried turn's TOOL_CALLS/TOOL_RESULT rows are its rows: a retry
        // re-asks from the user message and rebuilds the exchange fresh.
        val rows =
            listOf(
                textRow("t1", "USER", "a"),
                textRow("t1", "ASSISTANT", "b"),
                textRow("t2", "USER", "c"),
                ChatHistoryBuilder.PersistedRow(
                    "t2",
                    "ASSISTANT",
                    ChatHistoryBuilder.KIND_TOOL_CALLS,
                    """[{"id":"c1","name":"a","arguments":"{}"}]""",
                ),
                ChatHistoryBuilder.PersistedRow(
                    "t2",
                    "TOOL",
                    ChatHistoryBuilder.KIND_TOOL_RESULT,
                    """{"id":"c1","tool":"a","status":"SUCCEEDED","summary":"ok"}""",
                ),
                ChatHistoryBuilder.PersistedRow("t2", "ASSISTANT", ChatHistoryBuilder.KIND_TEXT, "done"),
            )
        val history = ChatHistoryBuilder.toModelMessages(ChatHistoryBuilder.rowsForTurn(rows, "t2"))
        assertEquals(listOf("a", "b", "c"), history.map { it.text })
        assertEquals(ModelRole.USER, history.last().role)
        // ...and the surviving history never mixes in the dropped tool exchange.
        assertTrue(history.none { it.toolCalls.isNotEmpty() || it.role == ModelRole.TOOL })
    }
}
