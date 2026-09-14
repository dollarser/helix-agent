package com.helix.runtime.cli.app

import com.helix.core.model.AssistantToolCall
import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.ModelToolSchema
import com.helix.core.model.ToolCallId
import com.helix.core.model.ToolName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexToolNamesTest {
    @Test fun systemInstructionsUseTheSubscriptionFieldAndAliasesStaySinglePass() {
        val request =
            request.copy(
                messages =
                    listOf(
                        ModelMessage(ModelRole.SYSTEM, "first instruction"),
                        ModelMessage(ModelRole.SYSTEM, "second instruction"),
                        ModelMessage(ModelRole.USER, "hi"),
                        ModelMessage(ModelRole.ASSISTANT, "", toolCalls = listOf(call)),
                        ModelMessage(ModelRole.TOOL, "result", toolCallId = call.id, toolName = tool.name),
                    ),
            )
        val encoded =
            kotlinx.serialization.json.Json
                .parseToJsonElement(
                    CodexSubscriptionModel.encodeSubscriptionRequest(request),
                ).let { it as kotlinx.serialization.json.JsonObject }
        assertEquals(
            kotlinx.serialization.json.JsonPrimitive("first instruction\n\nsecond instruction"),
            encoded["instructions"],
        )
        assertTrue(!encoded.getValue("input").toString().contains("\"system\""))
        val alias =
            CodexToolNames(request)
                .encode(request)
                .tools
                .single()
                .name.value
        val input = encoded.getValue("input") as kotlinx.serialization.json.JsonArray
        assertEquals(
            kotlinx.serialization.json.JsonPrimitive(alias),
            (input[1] as kotlinx.serialization.json.JsonObject)["name"],
        )
    }

    private val tool = ModelToolSchema(ToolName("fixture.echo"), "echo", """{"type":"object"}""")
    private val call = AssistantToolCall(ToolCallId("call1"), tool.name, """{"text":"x"}""")
    private val request = ModelRequest("fixture", listOf(ModelMessage(ModelRole.USER, "echo")), tools = listOf(tool))

    @Test fun aliasesAreStableAndCannotConfuseDottedAndUnderscoreNames() {
        val both = request.copy(tools = listOf(tool, tool.copy(name = ToolName("fixture_echo"))))
        val encoded = CodexToolNames(both).encode(both)
        val names = encoded.tools.map { it.name.value }
        assertTrue(names.all { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) })
        assertNotEquals(names[0], names[1])
        assertEquals(
            names[0],
            CodexToolNames(request)
                .encode(request)
                .tools
                .single()
                .name.value,
        )
        assertEquals(tool.inputSchemaJson, encoded.tools.first().inputSchemaJson)
    }

    @Test fun historyAndResultNamesRoundTripWithoutChangingIdsOrArguments() {
        val history =
            request.copy(
                messages =
                    request.messages +
                        listOf(
                            ModelMessage(ModelRole.ASSISTANT, "", toolCalls = listOf(call)),
                            ModelMessage(ModelRole.TOOL, "x", toolCallId = call.id, toolName = tool.name),
                        ),
            )
        val names = CodexToolNames(history)
        val wire = names.encode(history)
        val alias = wire.tools.single().name
        assertEquals(
            alias,
            wire.messages[1]
                .toolCalls
                .single()
                .name,
        )
        assertEquals(alias, wire.messages[2].toolName)
        assertEquals(call.id, wire.messages[2].toolCallId)
        assertEquals(
            call.argumentsJson,
            wire.messages[1]
                .toolCalls
                .single()
                .argumentsJson,
        )
        val start = ModelEvent.ToolCallStarted(0, call.id, alias.value)
        assertEquals(listOf(start.copy(name = tool.name.value)), names.decode(listOf(start)))
    }

    @Test fun unknownOrHistoryOnlyCallsCannotBecomeDispatchable() {
        val historyOnly =
            request.copy(
                tools = emptyList(),
                messages =
                    listOf(
                        ModelMessage(ModelRole.ASSISTANT, "", toolCalls = listOf(call)),
                        ModelMessage(ModelRole.TOOL, "x", toolCallId = call.id, toolName = tool.name),
                    ),
            )
        val names = CodexToolNames(historyOnly)
        val alias =
            names
                .encode(historyOnly)
                .messages
                .first()
                .toolCalls
                .single()
                .name.value
        for (wire in listOf(alias, "unknown")) {
            assertEquals(
                listOf(ModelEvent.Error(ModelErrorCode.PROTOCOL, false)),
                names.decode(
                    listOf(
                        ModelEvent.ToolCallStarted(0, call.id, wire),
                        ModelEvent.Completed(),
                    ),
                ),
            )
        }
    }
}
