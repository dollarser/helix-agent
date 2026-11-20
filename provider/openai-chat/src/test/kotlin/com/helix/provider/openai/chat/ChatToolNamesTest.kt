package com.helix.provider.openai.chat

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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatToolNamesTest {
    @Test fun declarationsAndOrderedHistoryUseSameAliasesWithoutChangingDomainRequest() {
        val names = listOf("tools_search", "tools_search_extra", "helix_tool_0", "files_list")
        val oldNames = listOf("tools_search", "mcp_retired_tool")
        val calls =
            oldNames.mapIndexed {
                index,
                name,
                ->
                AssistantToolCall(ToolCallId("old$index"), ToolName(name), "{ \"n\": 1 }")
            }
        val request =
            request(names).copy(
                messages =
                    listOf(ModelMessage(ModelRole.ASSISTANT, "", toolCalls = calls)) +
                        calls.map {
                            ModelMessage(ModelRole.TOOL, "result", toolCallId = it.id, toolName = it.name)
                        },
            )
        val map = ProviderToolNames(request)
        val body = Json.parseToJsonElement(adapter().requestBodyEncoder.encode(request)).jsonObject
        assertEquals(
            names.map(map::encode),
            body.getValue("tools").jsonArray.map {
                it.jsonObject
                    .getValue("function")
                    .jsonObject
                    .getValue("name")
                    .jsonPrimitive.content
            },
        )
        val messages = body.getValue("messages").jsonArray
        assertHistory(messages, calls, map)
        assertEquals(names, request.tools.map { it.name.value })
        assertEquals(oldNames, request.messages[0].toolCalls.map { it.name.value })
        assertProtocolFailure(request, fragment(0, "new", map.encode("mcp_retired_tool"), "{}"))
    }

    @Test fun splitNamesAndInterleavedCallsRestoreCanonicalIdentitiesAtEveryByteBoundary() {
        val request = request(listOf("files_read", "files_read_extra", "mcp_server_tool"))
        val map = ProviderToolNames(request)
        val first = map.encode("files_read")
        val second = map.encode("mcp_server_tool")
        val stream =
            fragment(0, "a", first.take(3), "") + fragment(1, "b", second.take(2)) +
                fragment(1, name = second.drop(2), arguments = "{\"n\":") +
                fragment(0, name = first.drop(3), arguments = "{}") + fragment(1, arguments = "1}") + FINISH_SSE
        val expected =
            listOf(
                ModelEvent.ToolCallStarted(1, ToolCallId("b"), "mcp_server_tool"),
                ModelEvent.ToolArgumentsDelta(1, "{\"n\":"),
                ModelEvent.ToolCallStarted(0, ToolCallId("a"), "files_read"),
                ModelEvent.ToolArgumentsDelta(0, "{}"),
                ModelEvent.ToolArgumentsDelta(1, "1}"),
                ModelEvent.ToolCallFinished(0),
                ModelEvent.ToolCallFinished(1),
                ModelEvent.Completed("tool_calls"),
            )
        for (size in listOf(1, 2, 3, 7, 64, stream.length)) assertEquals(expected, decode(request, stream, size))
    }

    @Test fun exactPrefixDoesNotPrematurelyBindAndEmptyArgumentsDoNotCloseName() {
        val request = request(listOf("read", "read_more"))
        val decoder = adapter().newDecoder(request)
        assertTrue(decoder.feed(fragment(0, "a", "read", "").toByteArray()).isEmpty())
        val events = decoder.feed((fragment(0, name = "_more", arguments = "{}") + FINISH_SSE).toByteArray())
        assertEquals("read_more", events.filterIsInstance<ModelEvent.ToolCallStarted>().single().name)
        assertFalse(events.any { it is ModelEvent.Error })
    }

    @Test fun emptyArgumentCallsResolveOnlyAtToolFinishAndMissingNamesFailClosed() {
        val request = request(listOf("files_read"))
        val name = ProviderToolNames(request).encode("files_read")
        val events = decode(request, fragment(0, "a") + fragment(0, name = name) + FINISH_SSE)
        assertEquals(
            listOf(
                ModelEvent.ToolCallStarted(0, ToolCallId("a"), "files_read"),
                ModelEvent.ToolCallFinished(0),
                ModelEvent.Completed("tool_calls"),
            ),
            events,
        )
        assertProtocolFailure(request, fragment(0, "a") + FINISH_SSE)
        assertProtocolFailure(request, fragment(0, "a", arguments = "{}"))
    }

    @Test fun unknownUnmappedIllegalAndTooLongNamesNeverEmitCalls() {
        val request = request(listOf("files_read", "read"))
        for (name in listOf("missing", "helix_tool_999", "files.read", "a".repeat(65), "a b", "工具")) {
            assertProtocolFailure(request, fragment(0, "a", name, "{}"))
        }
        assertProtocolFailure(request, fragment(0, "a", "a".repeat(40)) + fragment(0, name = "b".repeat(25)))
        assertProtocolFailure(request(emptyList()), fragment(0, "a", "read", "{}"))
    }

    @Test fun laterNameMutationMalformedFieldsAndTruncatedNamesFailClosed() {
        val request = request(listOf("read", "read_more"))
        val events = decode(request, fragment(0, "a", "read", "{}") + fragment(0, name = "_more"))
        assertEquals(1, events.filterIsInstance<ModelEvent.Error>().size)
        assertFalse(events.any { it is ModelEvent.Completed || it is ModelEvent.ToolCallFinished })
        for (field in listOf("\"name\":42", "\"name\":{}", "\"arguments\":[]")) {
            val stream =
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[" +
                    "{\"index\":0,\"id\":\"a\",\"function\":{$field}}]}}]}\n\n"
            assertProtocolFailure(request, stream)
        }
        val decoder = adapter().newDecoder(request)
        assertTrue(decoder.feed(fragment(0, "a", "rea").toByteArray()).isEmpty())
        assertEquals(listOf(ModelEvent.Error(ModelErrorCode.PROTOCOL, true)), decoder.finish())
        assertTrue(decoder.finish().isEmpty())
    }

    @Test fun countIndexAndWireNameBoundariesRemainEnforced() {
        val name = "a".repeat(64)
        val request = request(listOf(name))
        val max = (0 until 32).joinToString("") { fragment(it, "c$it", name, "{}") } + FINISH_SSE
        val events = decode(request, max)
        assertEquals(32, events.filterIsInstance<ModelEvent.ToolCallFinished>().size)
        assertEquals(ModelEvent.Completed("tool_calls"), events.last())
        val overflow = decode(request, (0..32).joinToString("") { fragment(it, "c$it", name, "{}") })
        assertEquals(32, overflow.filterIsInstance<ModelEvent.ToolCallStarted>().size)
        assertEquals(ModelEvent.Error(ModelErrorCode.PROTOCOL, false), overflow.last())
        assertEquals(
            1024,
            decode(
                request,
                fragment(1024, "a", name, "{}") + FINISH_SSE,
            ).filterIsInstance<ModelEvent.ToolCallStarted>().single().index,
        )
        assertProtocolFailure(request, fragment(1025, "a", name, "{}"))
    }

    @Test fun duplicateStartsAndOrphanFragmentsFailBeforeAnyCompletion() {
        val request = request(listOf("read"))
        val first = fragment(0, "a", "read", "{}")
        for (next in listOf(fragment(0, "b", "read"), fragment(1, "a", "read"))) {
            val events = decode(request, first + next + FINISH_SSE)
            assertEquals(
                listOf(
                    ModelEvent.ToolCallStarted(0, ToolCallId("a"), "read"),
                    ModelEvent.ToolArgumentsDelta(0, "{}"),
                    ModelEvent.Error(ModelErrorCode.PROTOCOL, false),
                ),
                events,
            )
        }
        assertProtocolFailure(request, fragment(0, name = "read"))
        assertProtocolFailure(request, fragment(0, arguments = "{}"))
    }

    private fun assertHistory(
        messages: JsonArray,
        calls: List<AssistantToolCall>,
        map: ProviderToolNames,
    ) {
        val encodedCalls = messages[0].jsonObject.getValue("tool_calls").jsonArray
        assertEquals(
            calls.map { map.encode(it.name.value) },
            encodedCalls.map {
                it.jsonObject
                    .getValue("function")
                    .jsonObject
                    .getValue("name")
                    .jsonPrimitive.content
            },
        )
        assertEquals(
            calls.map { it.id.value },
            encodedCalls.map {
                it.jsonObject
                    .getValue("id")
                    .jsonPrimitive.content
            },
        )
        assertEquals(
            calls.map { it.argumentsJson },
            encodedCalls.map {
                it.jsonObject
                    .getValue("function")
                    .jsonObject
                    .getValue("arguments")
                    .jsonPrimitive.content
            },
        )
        assertEquals(
            calls.map {
                it.id.value
            },
            messages.drop(1).map {
                it.jsonObject
                    .getValue("tool_call_id")
                    .jsonPrimitive.content
            },
        )
    }

    private fun assertProtocolFailure(
        request: ModelRequest,
        stream: String,
    ) {
        assertEquals(listOf(ModelEvent.Error(ModelErrorCode.PROTOCOL, false)), decode(request, stream + FINISH_SSE))
    }

    private fun decode(
        request: ModelRequest,
        stream: String,
        size: Int = 7,
    ): List<ModelEvent> {
        val decoder = adapter().newDecoder(request)
        return stream
            .toByteArray()
            .asList()
            .chunked(
                size,
            ).flatMap { decoder.feed(it.toByteArray()) } + decoder.finish() +
            decoder.finish()
    }

    private fun adapter() = OpenAiChatAdapter { error("no images") }

    private fun request(names: List<String>) =
        ModelRequest(
            "synthetic",
            listOf(ModelMessage(ModelRole.USER, "test")),
            tools = names.map { ModelToolSchema(ToolName(it), "test", """{"type":"object"}""") },
        )

    private fun fragment(
        index: Int,
        id: String? = null,
        name: String? = null,
        arguments: String? = null,
    ): String {
        val fields =
            listOfNotNull(
                name?.let {
                    "\"name\":${JsonPrimitive(it)}"
                },
                arguments?.let { "\"arguments\":${JsonPrimitive(it)}" },
            ).joinToString(",")
        val callId = id?.let { ",\"id\":${JsonPrimitive(it)}" }.orEmpty()
        return "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[" +
            "{\"index\":$index$callId,\"function\":{$fields}}]}}]}\n\n"
    }

    private companion object {
        const val FINISH_SSE =
            "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n"
    }
}
