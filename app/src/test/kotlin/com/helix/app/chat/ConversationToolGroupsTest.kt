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

    @Test fun preservesOrderAndBreaksAtMessagesPhasesAndUncertainCalls() {
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
        assertEquals(6, grouped.size)
        assertTrue(grouped[1] is ConversationContent.Tool)
        val flattened =
            grouped.flatMap {
                if (it is ConversationContent.ToolGroup) it.values.map(ConversationContent::Tool) else listOf(it)
            }
        assertEquals(input, flattened)
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
