package com.helix.provider.openai.responses

import com.helix.core.model.AssistantToolCall
import com.helix.core.model.ModelErrorCode
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

class ResponsesToolNamesTest {
    @Test fun namesAreLegalUniqueAndReversibleIncludingLiteralAliases() {
        val names =
            listOf(
                "files_read",
                "files_read_extra",
                "files-read",
                "helix_tool_0",
                "mcp_server_tool",
                "a".repeat(64),
            )
        val mapping = ProviderToolNames(request(names))
        val encoded = names.map(mapping::encode)
        assertTrue(encoded.all { Regex("[a-zA-Z0-9_-]{1,64}").matches(it) })
        assertEquals(names.size, encoded.toSet().size)
        assertEquals(names, encoded.map(mapping::decode))
        assertEquals("files_read", mapping.encode("files_read"))
        assertEquals("helix_tool_0", mapping.encode("helix_tool_0"))
        assertEquals(null, mapping.decode("unknown"))
        assertEquals(encoded, names.map(ProviderToolNames(request(names.reversed()))::encode))
    }

    @Test fun schemaAndHistoricalCallsUseSameNamesAndStreamRestoresDomainIdentity() {
        val historical = AssistantToolCall(ToolCallId("previous"), ToolName("files_read"), "{}")
        val request =
            request(listOf("files_read", "files_read_extra")).copy(
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
        assertEquals("files_read", alias)
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
        assertEquals("files_read", event.name)
    }

    @Test fun historicalOnlyToolsAreEncodedAndIndependentRequestsDoNotLeakNames() {
        val first = request(listOf("files_read"))
        val second = request(listOf("browser_click"))
        val firstMap = ProviderToolNames(first)
        val secondMap = ProviderToolNames(second)
        val firstDecoder = OpenAiResponsesAdapter { error("no images") }.newDecoder(first)
        val secondDecoder = OpenAiResponsesAdapter { error("no images") }.newDecoder(second)
        assertEquals(
            "browser_click",
            (secondDecoder.feed(start(secondMap.encode("browser_click"))).single() as ModelEvent.ToolCallStarted).name,
        )
        assertEquals(
            "files_read",
            (firstDecoder.feed(start(firstMap.encode("files_read"))).single() as ModelEvent.ToolCallStarted).name,
        )
        val historyOnly =
            first.copy(
                tools = emptyList(),
                messages =
                    listOf(
                        ModelMessage(
                            ModelRole.ASSISTANT,
                            "",
                            toolCalls = listOf(AssistantToolCall(ToolCallId("old"), ToolName("mcp_old_tool"), "{}")),
                        ),
                        ModelMessage(
                            ModelRole.TOOL,
                            "old result",
                            toolCallId = ToolCallId("old"),
                            toolName = ToolName("mcp_old_tool"),
                        ),
                    ),
            )
        val map = ProviderToolNames(historyOnly)
        assertEquals(null, map.decode(map.encode("mcp_old_tool")))
        val json =
            Json
                .parseToJsonElement(
                    ResponsesRequestEncoder { error("no images") }.encode(historyOnly),
                ).jsonObject
        assertEquals(
            map.encode("mcp_old_tool"),
            json
                .getValue("input")
                .jsonArray[0]
                .jsonObject
                .getValue("name")
                .jsonPrimitive.content,
        )
    }

    @Test fun unknownCanonicalAndHistoricalOnlyNamesFailClosedAcrossByteChunks() {
        val old = AssistantToolCall(ToolCallId("old"), ToolName("mcp_retired_tool"), "{}")
        val request =
            request(listOf("files_read")).copy(
                messages =
                    listOf(
                        ModelMessage(ModelRole.ASSISTANT, "", toolCalls = listOf(old)),
                        ModelMessage(ModelRole.TOOL, "ok", toolCallId = old.id, toolName = old.name),
                    ),
            )
        val map = ProviderToolNames(request)
        for (name in listOf("unknown", "files.read", "helix_tool_999", map.encode(old.name.value), "a".repeat(65))) {
            val decoder = OpenAiResponsesAdapter { error("no images") }.newDecoder(request)
            val events = start(name).flatMap { decoder.feed(byteArrayOf(it)) } + decoder.finish() + decoder.finish()
            assertEquals(listOf(ModelEvent.Error(ModelErrorCode.PROTOCOL, false)), events)
        }
        val decoder = OpenAiResponsesAdapter { error("no images") }.newDecoder(request)
        val events = start(map.encode("files_read")).flatMap { decoder.feed(byteArrayOf(it)) }
        assertEquals("files_read", events.filterIsInstance<ModelEvent.ToolCallStarted>().single().name)
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
