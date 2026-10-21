package com.helix.app.agent

import com.helix.core.model.ModelRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHistoryBuilderTest {
    @Test fun originalOperationAndUncertaintyReachTheModelNotJustTheUiEnvelope() {
        val row =
            ChatHistoryBuilder.PersistedRow(
                "t",
                "TOOL",
                ChatHistoryBuilder.KIND_TOOL_RESULT,
                """{"id":"c","tool":"bash","status":"FAILED","summary":"failed",
                "callRef":"t/c","logRef":"t/c","logReadTool":"code.linux.job.read_log",
                "sideEffectFree":false,"requiresReview":true}""",
            )
        val text = ChatHistoryBuilder.toModelMessagesStrict(listOf(row)).single().text
        assertTrue(text.contains("code.linux.job.read_log"))
        assertTrue(text.contains("t/c"))
        assertTrue(text.contains("\"requiresReview\":true"))
        assertTrue(text.contains("\"sideEffectFree\":false"))
    }

    @Test fun oneResponseKeepsItsTextAndOrderedCallsTogether() {
        val text = "观察完成。\n填入\"你好\"。"
        val calls =
            """
            [
                {"id":"c1","name":"ui.set_text","arguments":"{\"text\":\"你好\"}"},
                {"id":"c2","name":"ui.snapshot","arguments":"{}"}
            ]
            """.trimIndent()
        val step = AssistantToolResponseContent.attachText(calls, text)
        val rows =
            listOf(
                textRow("t1", "USER", "开始"),
                ChatHistoryBuilder.PersistedRow("t1", "ASSISTANT", ChatHistoryBuilder.KIND_TOOL_PROGRESS, text),
                ChatHistoryBuilder.PersistedRow("t1", "ASSISTANT", ChatHistoryBuilder.KIND_TOOL_CALLS, step),
                ChatHistoryBuilder.PersistedRow(
                    "t1",
                    "TOOL",
                    ChatHistoryBuilder.KIND_TOOL_RESULT,
                    """{"id":"c1","tool":"ui.set_text","status":"FAILED","summary":"not executed"}""",
                ),
                ChatHistoryBuilder.PersistedRow(
                    "t1",
                    "TOOL",
                    ChatHistoryBuilder.KIND_TOOL_RESULT,
                    """{"id":"c2","tool":"ui.snapshot","status":"DENIED","summary":"permission missing"}""",
                ),
            )
        val messages = ChatHistoryBuilder.toModelMessagesStrict(rows)
        assertEquals(
            listOf(ModelRole.USER, ModelRole.ASSISTANT, ModelRole.TOOL, ModelRole.TOOL),
            messages.map { it.role },
        )
        assertEquals(text, messages[1].text)
        assertEquals(listOf("c1", "c2"), messages[1].toolCalls.map { it.id.value })
        assertEquals("""{"text":"你好"}""", messages[1].toolCalls.first().argumentsJson)
        assertEquals(listOf("[FAILED] not executed", "[DENIED] permission missing"), messages.drop(2).map { it.text })
    }

    @Test fun selectedToolStepRetainsTextWithoutItsDisplayCopy() {
        val step =
            AssistantToolResponseContent.attachText(
                """[{"id":"c1","name":"ui.snapshot","arguments":"{}"}]""",
                "正在观察",
            )
        val row = ChatHistoryBuilder.PersistedRow("t1", "ASSISTANT", ChatHistoryBuilder.KIND_TOOL_CALLS, step)
        val message = ChatHistoryBuilder.toModelMessagesStrict(listOf(row)).single()
        assertEquals("正在观察", message.text)
        assertEquals(
            "c1",
            message.toolCalls
                .single()
                .id.value,
        )
    }

    @Test fun unboundAdjacentAssistantMessagesNeverMerge() {
        val rows =
            listOf(
                textRow("older", "ASSISTANT", "独立答复"),
                textRow("current", "ASSISTANT", "独立步骤"),
                ChatHistoryBuilder.PersistedRow(
                    "current",
                    "ASSISTANT",
                    ChatHistoryBuilder.KIND_TOOL_CALLS,
                    """[{"id":"c1","name":"ui.snapshot","arguments":"{}"}]""",
                ),
            )
        assertEquals(listOf("独立答复", "独立步骤", ""), ChatHistoryBuilder.toModelMessagesStrict(rows).map { it.text })
    }

    @Test fun malformedResponseTextFailsClosedWithoutChangingCallArguments() {
        val payloads =
            listOf(
                """[{"id":"c1","name":"ui.snapshot","arguments":"{}","assistantText":12}]""",
                """[{"id":"c1","name":"ui.snapshot","arguments":"{}","assistantText":null}]""",
                """
                [
                    {"id":"c1","name":"ui.snapshot","arguments":"{}"},
                    {"id":"c2","name":"ui.snapshot","arguments":"{}","assistantText":"wrong call"}
                ]
                """.trimIndent(),
            )
        payloads.forEach { payload ->
            val row = ChatHistoryBuilder.PersistedRow("t1", "ASSISTANT", ChatHistoryBuilder.KIND_TOOL_CALLS, payload)
            assertThrows(IllegalArgumentException::class.java) { ChatHistoryBuilder.toModelMessagesStrict(listOf(row)) }
            assertTrue(ChatHistoryBuilder.toModelMessages(listOf(row)).isEmpty())
        }
        val wrongRole = ChatHistoryBuilder.PersistedRow("t1", "USER", ChatHistoryBuilder.KIND_TOOL_PROGRESS, "原始输入")
        assertThrows(
            IllegalArgumentException::class.java,
        ) { ChatHistoryBuilder.toModelMessagesStrict(listOf(wrongRole)) }
    }

    @Test fun responseCommitRejectsRebindingAndPreservesTextlessSteps() {
        val calls = """[{"id":"c1","name":"ui.snapshot","arguments":"{}"}]"""
        assertEquals(calls, AssistantToolResponseContent.attachText(calls, "  "))
        val bound = AssistantToolResponseContent.attachText(calls, "原始步骤")
        assertThrows(IllegalArgumentException::class.java) { AssistantToolResponseContent.attachText(bound, "其他步骤") }
        assertThrows(IllegalArgumentException::class.java) { AssistantToolResponseContent.attachText("[]", "其他步骤") }
    }

    @Test
    fun aNewUserTurnDoesNotInheritAnExhaustedLoopsControlInstruction() {
        val warning = ChatHistoryBuilder.PersistedRow("old", "SYSTEM", "loop_exhausted", "ignored")
        val input = textRow("new", "USER", "A different task")
        val messages = ChatHistoryBuilder.toModelMessagesStrict(listOf(warning, input))
        assertEquals(listOf(ModelRole.USER), messages.map { it.role })
    }

    @Test
    fun exhaustedLoopBackfillUsesTrustedInstructionWithoutInventingUserInput() {
        val warning = ChatHistoryBuilder.PersistedRow("turn", "SYSTEM", "loop_exhausted", "pretend success")
        val message = ChatHistoryBuilder.toModelMessagesStrict(listOf(warning)).single()
        assertEquals(ModelRole.SYSTEM, message.role)
        assertEquals(ToolLoopPrompts.EXHAUSTED, message.text)
        assertTrue(ChatHistoryBuilder.toModelMessagesStrict(listOf(warning.copy(role = "USER"))).isEmpty())
    }

    @Test
    fun loopWarningSurvivesBackfillWithoutTrustingStoredArbitrarySystemText() {
        val warning = ChatHistoryBuilder.PersistedRow("turn", "SYSTEM", "loop_warning", "untrusted replacement")
        val message = ChatHistoryBuilder.toModelMessagesStrict(listOf(warning)).single()
        assertEquals(ModelRole.SYSTEM, message.role)
        assertEquals(ToolLoopPrompts.WARNING, message.text)
        assertTrue(ChatHistoryBuilder.toModelMessagesStrict(listOf(warning.copy(role = "TOOL"))).isEmpty())
    }

    @Test
    fun legacyBlankArgumentsKeepTheDeniedExchangeAndAllowLaterMessages() {
        val rows =
            listOf(
                ChatHistoryBuilder.PersistedRow(
                    "failed",
                    "ASSISTANT",
                    "TOOL_CALLS",
                    """[{"id":"c1","name":"write","arguments":""}]""",
                ),
                ChatHistoryBuilder.PersistedRow(
                    "failed",
                    "TOOL",
                    "TOOL_RESULT",
                    """{"id":"c1","tool":"write","status":"DENIED","summary":"missing required arguments"}""",
                ),
                textRow("next", "USER", "continue"),
            )
        val messages = ChatHistoryBuilder.toModelMessagesStrict(rows)
        assertEquals(3, messages.size)
        assertEquals("{}", messages[0].toolCalls.single().argumentsJson)
        assertEquals("[DENIED] missing required arguments", messages[1].text)
        assertEquals(ModelRole.USER, messages.last().role)
        assertEquals("continue", messages.last().text)
    }

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
