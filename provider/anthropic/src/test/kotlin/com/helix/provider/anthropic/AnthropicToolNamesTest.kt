package com.helix.provider.anthropic

import com.helix.core.model.AssistantToolCall
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.ModelToolSchema
import com.helix.core.model.ToolCallId
import com.helix.core.model.ToolName
import com.helix.provider.api.ProviderToolNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicToolNamesTest {
    @Test fun declarationsAndHistorySharePortableNamesWithoutAuthorizingRetiredTools() {
        val retired = AssistantToolCall(ToolCallId("old"), ToolName("mcp_retired_tool"), "{}")
        val request =
            request().copy(
                messages =
                    listOf(
                        ModelMessage(ModelRole.USER, "test"),
                        ModelMessage(ModelRole.ASSISTANT, "", toolCalls = listOf(retired)),
                        ModelMessage(ModelRole.TOOL, "ok", toolCallId = retired.id, toolName = retired.name),
                    ),
            )
        val names = ProviderToolNames(request)
        val body = Json.parseToJsonElement(adapter().requestBodyEncoder.encode(request)).jsonObject
        assertEquals(
            names.encode("tools_search"),
            body
                .getValue("tools")
                .jsonArray
                .first()
                .jsonObject
                .getValue("name")
                .jsonPrimitive.content,
        )
        val oldCall =
            body
                .getValue("messages")
                .jsonArray[1]
                .jsonObject
                .getValue("content")
                .jsonArray
                .last()
                .jsonObject
        assertEquals(names.encode(retired.name.value), oldCall.getValue("name").jsonPrimitive.content)
        assertTrue(
            adapter().newDecoder(request).feed(start(names.encode(retired.name.value))).single() is ModelEvent.Error,
        )
        assertEquals(
            "tools_search",
            request.tools
                .first()
                .name.value,
        )
    }

    @Test fun byteChunkedCallsDecodeCanonicalNamesAndRejectUnknownTools() {
        val request = request()
        val names = ProviderToolNames(request)
        for (size in listOf(1, 3, 64, 4096)) {
            val decoder = adapter().newDecoder(request)
            val events =
                start(
                    names.encode("tools_search"),
                ).toList().chunked(size).flatMap { decoder.feed(it.toByteArray()) }
            assertEquals("tools_search", events.filterIsInstance<ModelEvent.ToolCallStarted>().single().name)
        }
        for (name in listOf("tools.search", "unknown", "files_read")) {
            val events = adapter().newDecoder(request).feed(start(name))
            assertTrue(events.single() is ModelEvent.Error)
        }
    }

    private fun request() =
        ModelRequest(
            model = "synthetic",
            messages = listOf(ModelMessage(ModelRole.USER, "test")),
            tools = listOf(ModelToolSchema(ToolName("tools_search"), "search", """{"type":"object"}""")),
        )

    private fun adapter() = AnthropicAdapter { error("no images") }

    private fun start(name: String) =
        (
            "event: content_block_start\n" +
                "data: {\"type\":\"content_block_start\",\"index\":0," +
                "\"content_block\":{\"type\":\"tool_use\",\"id\":\"call1\",\"name\":\"$name\",\"input\":{}}}\n\n"
        ).toByteArray()
}
