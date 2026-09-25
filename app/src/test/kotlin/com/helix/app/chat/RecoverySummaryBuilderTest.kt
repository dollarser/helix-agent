package com.helix.app.chat

import com.helix.app.agent.ChatHistoryBuilder
import com.helix.app.agent.RecoveryContextPolicy
import com.helix.core.model.ModelRole
import com.helix.core.model.TurnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoverySummaryBuilderTest {
    @Test
    fun formatCarriesDurableFactsWithoutArgsAndBoundsLargeSummaries() {
        val text =
            RecoverySummaryBuilder.format(
                predecessorTurnId = "old-turn",
                state = TurnState.INTERRUPTED,
                errorCode = "PROCESS_DIED",
                calls =
                    listOf(
                        RecoverySummaryBuilder.ToolFact(
                            name = "write",
                            state = "COMPLETED",
                            resultStatus = "SUCCEEDED",
                            resultSummary = "ok",
                        ),
                        RecoverySummaryBuilder.ToolFact(
                            name = "remote.post",
                            state = "INTERRUPTED",
                            resultStatus = null,
                            resultSummary = "x".repeat(2_000),
                            reviewDecision = "ACKNOWLEDGED_UNKNOWN",
                        ),
                    ),
            )

        assertTrue(text.startsWith("[RecoverySummary]"))
        assertTrue(text.contains("Previous Turn: old-turn"))
        assertTrue(text.contains("write [COMPLETED] result=SUCCEEDED: ok"))
        assertTrue(text.contains("remote.post [INTERRUPTED] review=ACKNOWLEDGED_UNKNOWN"))
        assertTrue(text.contains("Do not blindly replay"))
        assertFalse(text.contains("arguments"))
        assertTrue(text.length <= 12_000)
    }

    @Test
    fun successorHistoryDropsPredecessorExecutionProtocolButKeepsUserIntent() {
        val rows =
            listOf(
                row("old", ModelRole.USER, ChatHistoryBuilder.KIND_TEXT, "do work", "u-old"),
                row("old", ModelRole.ASSISTANT, ChatHistoryBuilder.KIND_TEXT, "working", "a-old"),
                row("old", ModelRole.ASSISTANT, ChatHistoryBuilder.KIND_TOOL_CALLS, "[]", "tc-old"),
                row("old", ModelRole.TOOL, ChatHistoryBuilder.KIND_TOOL_RESULT, "{}", "tr-old"),
                row("next", ModelRole.USER, ChatHistoryBuilder.KIND_TEXT, "continue", "u-next"),
            )

        val kept = RecoveryContextPolicy.modelHistoryRows(rows, "old")

        assertEquals(listOf("u-old", "u-next"), kept.map { it.messageId })
    }

    private fun row(
        turnId: String,
        role: ModelRole,
        kind: String,
        content: String,
        id: String,
    ) = ChatHistoryBuilder.PersistedRow(turnId, role.name, kind, content, id)
}
