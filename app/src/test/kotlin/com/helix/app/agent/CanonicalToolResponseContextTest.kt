package com.helix.app.agent

import com.helix.core.agent.ContextSegments
import com.helix.core.agent.ContextSourceRow
import com.helix.core.agent.ContextSourceSnapshot
import com.helix.core.agent.TurnContextRequest
import com.helix.core.model.ReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CanonicalToolResponseContextTest {
    @Test fun compactionGroupsAndRemainingRequestKeepWholeOriginalResponses() {
        val rows = listOf(row("user", "USER", "TEXT", "开始")) + step(1, "第一步", "FAILED") + step(2, "第二步", "SUCCEEDED")
        val source = source(rows)
        val groups = ContextSegments.candidates(source, "turn")
        assertEquals(listOf(listOf("calls-1", "result-1")), groups.map { group -> group.map { it.id } })
        val messages = ChatHistoryBuilder.toModelMessagesStrict(rows)
        val request = TurnContextRequest("fixture", messages, emptyList(), 512, ReasoningEffort.OFF)
        val remaining =
            ContextSegments.remainingRequest(
                ContextSourceSnapshot(source, null, "turn", null, 0),
                groups.single().map { it.id }.toSet(),
                request,
            )
        assertNotNull(remaining)
        assertEquals(listOf("开始", "第二步", "[SUCCEEDED] result"), remaining!!.messages.map { it.text })
        assertEquals(
            "call-2",
            remaining.messages[1]
                .toolCalls
                .single()
                .id.value,
        )
    }

    @Test fun pendingToolResponseCannotBecomeASettledCompactionGroup() {
        val rows =
            step(1, "已执行", "SUCCEEDED") + step(2, "最后完成步骤", "SUCCEEDED") + step(3, "等待审批", "SUCCEEDED").dropLast(1)
        val groups = ContextSegments.candidates(source(rows), "turn")
        assertEquals(listOf(listOf("calls-1", "result-1")), groups.map { group -> group.map { it.id } })
    }

    private fun step(
        index: Int,
        text: String,
        status: String,
    ) = listOf(
        row("progress-$index", "ASSISTANT", ChatHistoryBuilder.KIND_TOOL_PROGRESS, text),
        row(
            "calls-$index",
            "ASSISTANT",
            ChatHistoryBuilder.KIND_TOOL_CALLS,
            AssistantToolResponseContent.attachText(
                """[{"id":"call-$index","name":"ui.snapshot","arguments":"{}"}]""",
                text,
            ),
        ),
        row(
            "result-$index",
            "TOOL",
            ChatHistoryBuilder.KIND_TOOL_RESULT,
            """{"id":"call-$index","tool":"ui.snapshot","status":"$status","summary":"result"}""",
        ),
    )

    private fun source(rows: List<ChatHistoryBuilder.PersistedRow>) =
        rows.mapIndexed { index, row ->
            ContextSourceRow(
                row.messageId!!,
                row.turnId,
                index.toLong(),
                row.role,
                row.kind!!,
                row.content,
                0,
                row.kind == ChatHistoryBuilder.KIND_TOOL_CALLS,
                ChatHistoryBuilder.toModelMessagesStrict(listOf(row)),
            )
        }

    private fun row(
        id: String,
        role: String,
        kind: String,
        content: String,
    ) = ChatHistoryBuilder.PersistedRow("turn", role, kind, content, id)
}
