package com.helix.app.localmodel

import com.helix.core.model.AssistantToolCall
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.ToolCallId
import com.helix.core.model.ToolName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRuntimeCodecTest {
    @Test
    fun truncatedPayloadRetainsUsageWithoutReadingPartialContent() {
        val response = """{"inputTokens":12,"outputTokens":1,"finish":"length","calls":"unfinished"}"""
        assertEquals(
            listOf(
                ModelEvent.Usage(12, 1),
                ModelEvent.Error(com.helix.core.model.ModelErrorCode.LOCAL_OUTPUT_LIMIT, false),
            ),
            LocalRuntimeCodec.decode(response.toByteArray()),
        )
    }

    @Test
    fun toolHistoryKeepsCallIdentityAndBusinessArguments() {
        val call = AssistantToolCall(ToolCallId("call-1"), ToolName("files.read"), "{\"path\":\"a\"}")
        val request =
            ModelRequest(
                "asset",
                listOf(
                    ModelMessage(ModelRole.USER, "Read a"),
                    ModelMessage(ModelRole.ASSISTANT, "", toolCalls = listOf(call)),
                    ModelMessage(ModelRole.TOOL, "file contents", toolCallId = call.id, toolName = call.name),
                ),
            )
        val decoded = Json.parseToJsonElement(LocalRuntimeCodec.encode(request).toString(Charsets.UTF_8)).jsonObject
        val history = decoded.getValue("history").jsonArray
        assertEquals(
            "call-1",
            history[2]
                .jsonObject
                .getValue("callId")
                .jsonPrimitive.content,
        )
        assertEquals(
            call.argumentsJson,
            history[1]
                .jsonObject
                .getValue("calls")
                .jsonArray
                .single()
                .jsonObject
                .getValue("arguments")
                .jsonPrimitive.content,
        )
    }

    @Test
    fun outputIsNormalizedToTheExistingToolStream() {
        val response = """{
            "text":"", "reasoning":"check", "calls":[{"name":"files.read","arguments":"{}"}],
            "inputTokens":12, "outputTokens":8, "finish":"stop"
        }"""
        val events = LocalRuntimeCodec.decode(response.toByteArray())
        assertTrue(events[0] is ModelEvent.ReasoningDelta)
        assertEquals("files.read", (events[1] as ModelEvent.ToolCallStarted).name)
        assertEquals(ModelEvent.ToolArgumentsDelta(0, "{}"), events[2])
        assertEquals(ModelEvent.Completed("tool_calls"), events.last())
        assertEquals(
            listOf(
                ModelEvent.Usage(12, 8),
                ModelEvent.Error(com.helix.core.model.ModelErrorCode.LOCAL_OUTPUT_LIMIT, false),
            ),
            LocalRuntimeCodec.decode(response.replace("stop", "length").toByteArray()),
        )
    }
}
