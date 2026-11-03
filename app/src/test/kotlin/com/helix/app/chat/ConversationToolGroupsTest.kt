package com.helix.app.chat

import com.helix.core.model.ToolCallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationToolGroupsTest {
    private fun tool(
        id: String,
        phase: Long = 1,
        state: ToolCallState = ToolCallState.COMPLETED,
    ) = ConversationContent.Tool(
        ToolTimelineRow(
            "turn",
            id,
            "read",
            "{}",
            "arbitrary translated text",
            null,
            null,
            position = ToolTimelinePosition(phase, id.last().digitToInt()),
            state = state,
        ),
    )

    @Test fun preservesOrderAcrossModelPhasesButBreaksAtVisibleMessagesAndUncertainCalls() {
        val message = ConversationContent.Message(MessageUi("answer", "assistant", "hello"), 2)
        val input =
            listOf(
                tool("c0"),
                tool("c1"),
                tool("c2", state = ToolCallState.NEEDS_REVIEW),
                tool("c3"),
                tool("c4", phase = 3),
                message,
                tool("c5", phase = 3),
            )
        val grouped = groupCompletedTools(input)
        assertEquals(listOf("c0", "c1"), (grouped.first() as ConversationContent.ToolGroup).values.map { it.callId })
        assertEquals(5, grouped.size)
        assertTrue(grouped[1] is ConversationContent.Tool)
        assertEquals(listOf("c3", "c4"), (grouped[2] as ConversationContent.ToolGroup).values.map { it.callId })
        val flattened =
            grouped.flatMap {
                if (it is ConversationContent.ToolGroup) it.values.map(ConversationContent::Tool) else listOf(it)
            }
        assertEquals(input, flattened)
    }

    @Test fun normalOneCallPerResponseBecomesOneGroupWithoutCrossingTurnsOrActiveCalls() {
        val first = tool("c0", phase = 2)
        val second = tool("c1", phase = 5)
        val running = tool("c2", phase = 8, state = ToolCallState.RUNNING)
        val otherTurn = tool("c3", phase = 11).let { it.copy(value = it.value.copy(turnId = "another")) }
        val grouped = groupCompletedTools(listOf(first, second, running, otherTurn))
        assertEquals(listOf("c0", "c1"), (grouped.first() as ConversationContent.ToolGroup).values.map { it.callId })
        assertEquals(listOf(running, otherTurn), grouped.drop(1))
        val settled = running.copy(value = running.value.copy(state = ToolCallState.COMPLETED))
        assertEquals(
            3,
            (groupCompletedTools(listOf(first, second, settled)).single() as ConversationContent.ToolGroup).values.size,
        )
    }

    @Test fun unsettleDoesNotDisappearInsideCompletedGroupAndUnknownPositionsDoNotGroup() {
        val unsettled =
            tool("c1").let {
                it.copy(
                    value =
                        it.value.copy(
                            toolName = "ui.scroll",
                            resultSummary = """{"observationStatus":"UNSETTLED"}""",
                        ),
                )
            }
        assertEquals(3, groupCompletedTools(listOf(tool("c0"), unsettled, tool("c2"))).size)
        val unknown = tool("c0").let { it.copy(value = it.value.copy(position = null)) }
        val other = unknown.copy(value = unknown.value.copy(callId = "x"))
        assertEquals(2, groupCompletedTools(listOf(unknown, other)).size)
    }

    @Test fun routeEvidenceIsTypedAndDoesNotAcceptModelTextOrMalformedJson() {
        val row =
            tool("c0").value.copy(
                toolName = "ui.device",
                resultSummary = """{
"displayId":4,"executionDisplay":"background",
"packageName":"com.example.fixture"
}""",
            )
        assertTrue(requireNotNull(toolObservationPresentation(row)).background)
        assertEquals(null, toolObservationPresentation(row.copy(toolName = "mcp.foo")))
        val malformed = row.copy(resultSummary = """{"displayId":{},"executionDisplay":"background"}""")
        assertEquals(null, toolObservationPresentation(malformed))
        assertEquals(null, toolObservationPresentation(row.copy(resultSummary = "background com.example.fixture")))
    }
}
