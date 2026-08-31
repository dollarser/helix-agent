package com.helix.app.chat

import com.helix.core.model.ModelRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHistoryBuilderTest {
    private val row = { turnId: String?, role: String, content: String? ->
        ChatHistoryBuilder.PersistedRow(turnId, role, content)
    }

    @Test
    fun mapsUserAndAssistantRowsInPersistedOrder() {
        val messages =
            ChatHistoryBuilder.toModelMessages(
                listOf(
                    row("t1", "USER", "你好"),
                    row("t1", "ASSISTANT", "你好！"),
                    row("t2", "USER", "帮我总结"),
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
                    row("t1", "USER", "问题"),
                    row("t1", "ASSISTANT", null), // stopped turn with no output
                    row("t1", "ASSISTANT", "   "),
                    row("t2", "USER", "再来一次"),
                ),
            )
        assertEquals(2, messages.size)
        assertEquals("问题", messages[0].text)
        assertEquals("再来一次", messages[1].text)
    }

    @Test
    fun nonChatRolesAreSkippedSoTheM2RequestNeverInventsToolContext() {
        val messages =
            ChatHistoryBuilder.toModelMessages(
                listOf(
                    row("t1", "USER", "hi"),
                    row("t1", "TOOL", "some tool row"),
                    row("t1", "SYSTEM", "some system row"),
                ),
            )
        assertEquals(1, messages.size)
        assertTrue(messages[0].text == "hi")
    }

    @Test
    fun freshSendKeepsEveryRowInOrder() {
        val rows =
            listOf(
                row("t1", "USER", "a"),
                row("t1", "ASSISTANT", "b"),
                row("t2", "USER", "c"),
            )
        assertEquals(rows, ChatHistoryBuilder.rowsForTurn(rows, null))
    }

    @Test
    fun retryExcludesTheRetriedTurnsRowsAndReappendsItsUserMessageLast() {
        val rows =
            listOf(
                row("t1", "USER", "a"),
                row("t1", "ASSISTANT", "b"),
                row("t2", "USER", "c"),
                // t2 failed and left an assistant row
                row("t2", "ASSISTANT", "partial"),
                row("t3", "USER", "d"),
                row("t3", "ASSISTANT", "e"),
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
                row("t1", "USER", "a"),
                row("t1", "ASSISTANT", "b"),
                row("t2", "USER", "c"),
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
                row("t1", "USER", "a"),
                row("t1", "ASSISTANT", "b"),
                row("t2", "ASSISTANT", "orphan"),
            )
        val history = ChatHistoryBuilder.toModelMessages(ChatHistoryBuilder.rowsForTurn(rows, "t2"))
        assertEquals(listOf("a", "b"), history.map { it.text })
        assertEquals(ModelRole.ASSISTANT, history.last().role)
    }
}
