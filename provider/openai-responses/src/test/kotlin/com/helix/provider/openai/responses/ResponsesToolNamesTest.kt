package com.helix.provider.openai.responses

import com.helix.core.model.AssistantToolCall
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.ModelToolSchema
import com.helix.core.model.ToolCallId
import com.helix.core.model.ToolName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponsesToolNamesTest {
    @Test fun namesAreLegalUniqueAndReversibleIncludingLiteralAliases() {
        val names =
            listOf(
                "files.read",
                "files_read",
                "files-read",
                "helix_tool_0",
                "mcp.server.tool",
                "a".repeat(64) + ".b",
            )
        val mapping = ResponsesToolNames(request(names))
        val encoded = names.map(mapping::encode)
        assertTrue(encoded.all { Regex("[a-zA-Z0-9_-]{1,64}").matches(it) })
        assertEquals(names.size, encoded.toSet().size)
        assertEquals(names, encoded.map(mapping::decode))
        assertEquals("files_read", mapping.encode("files_read"))
        assertEquals("helix_tool_0", mapping.encode("helix_tool_0"))
        assertEquals("unknown", mapping.decode("unknown"))
        assertEquals(encoded, names.map(ResponsesToolNames(request(names.reversed()))::encode))
    }

    @Test fun schemaAndHistoricalCallsUseSameNamesAndStreamRestoresDomainIdentity() {
        val historical = AssistantToolCall(ToolCallId("previous"), ToolName("files.read"), "{}")
        val request =
            request(listOf("files.read", "files_read")).copy(
                messages =
                    listOf(
                        ModelMessage(ModelRole.ASSISTANT, "", toolCalls = listOf(historical)),
                        ModelMessage(ModelRole.TOOL, "ok", toolCallId = historical.id, toolName = historical.name),
                    ),
            )
        val adapter = OpenAiResponsesAdapter { error("no images") }
        val json = Json.parseToJsonElement(adapter.requestBodyEncoder.encode(request)).jsonObject
        val alias =
            json
                .getValue("tools")
                .jsonArray[0]
                .jsonObject
                .getValue("name")
                .jsonPrimitive.content
        assertNotEquals("files.read", alias)
        assertEquals(
            alias,
            json
                .getValue("input")
                .jsonArray[0]
                .jsonObject
                .getValue("name")
                .jsonPrimitive.content,
        )
        assertEquals(
            "previous",
            json
                .getValue("input")
                .jsonArray[1]
                .jsonObject
                .getValue("call_id")
                .jsonPrimitive.content,
        )
        val event = adapter.newDecoder(request).feed(start(alias)).single() as ModelEvent.ToolCallStarted
        assertEquals("files.read", event.name)
    }

    @Test fun historicalOnlyToolsAreEncodedAndIndependentRequestsDoNotLeakNames() {
        val first = request(listOf("files.read"))
        val second = request(listOf("browser.click"))
        val firstMap = ResponsesToolNames(first)
        val secondMap = ResponsesToolNames(second)
        val firstDecoder = OpenAiResponsesAdapter { error("no images") }.newDecoder(first)
        val secondDecoder = OpenAiResponsesAdapter { error("no images") }.newDecoder(second)
        assertEquals(
            "browser.click",
            (secondDecoder.feed(start(secondMap.encode("browser.click"))).single() as ModelEvent.ToolCallStarted).name,
        )
        assertEquals(
            "files.read",
            (firstDecoder.feed(start(firstMap.encode("files.read"))).single() as ModelEvent.ToolCallStarted).name,
        )
        val historyOnly =
            first.copy(
                tools = emptyList(),
                messages =
                    listOf(
                        ModelMessage(
                            ModelRole.ASSISTANT,
                            "",
                            toolCalls = listOf(AssistantToolCall(ToolCallId("old"), ToolName("mcp.old.tool"), "{}")),
                        ),
                        ModelMessage(
                            ModelRole.TOOL,
                            "old result",
                            toolCallId = ToolCallId("old"),
                            toolName = ToolName("mcp.old.tool"),
                        ),
                    ),
            )
        val map = ResponsesToolNames(historyOnly)
        assertEquals("mcp.old.tool", map.decode(map.encode("mcp.old.tool")))
        val json =
            Json
                .parseToJsonElement(
                    ResponsesRequestEncoder { error("no images") }.encode(historyOnly),
                ).jsonObject
        assertEquals(
            map.encode("mcp.old.tool"),
            json
                .getValue("input")
                .jsonArray[0]
                .jsonObject
                .getValue("name")
                .jsonPrimitive.content,
        )
    }

    private fun request(names: List<String>) =
        ModelRequest(
            model = "synthetic",
            messages = listOf(ModelMessage(ModelRole.USER, "test")),
            tools = names.map { ModelToolSchema(ToolName(it), "test", """{"type":"object","properties":{}}""") },
        )

    private fun start(name: String): ByteArray =
        (
            "event: response.output_item.added\n" +
                "data: {\"output_index\":0,\"item\":{\"type\":\"function_call\"," +
                "\"call_id\":\"call1\",\"name\":\"$name\"}}\n\n"
        ).toByteArray()
}
