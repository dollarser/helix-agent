package com.helix.provider.anthropic

import com.helix.core.model.ArtifactRef
import com.helix.core.model.AssistantToolCall
import com.helix.core.model.ImageReference
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
import org.junit.Test

class ToolImageEncodingTest {
    private fun request() =
        ModelRequest(
            "vision",
            listOf(
                ModelMessage(ModelRole.USER, "Inspect the output"),
                ModelMessage(
                    ModelRole.ASSISTANT,
                    "",
                    toolCalls =
                        listOf(
                            AssistantToolCall(ToolCallId("a"), ToolName("view_image"), "{}"),
                            AssistantToolCall(ToolCallId("b"), ToolName("read"), "{}"),
                        ),
                ),
                ModelMessage(
                    ModelRole.TOOL,
                    "image prepared",
                    listOf(ImageReference(ArtifactRef("pixels"), "image/png")),
                    ToolCallId("a"),
                    ToolName("view_image"),
                ),
                ModelMessage(ModelRole.TOOL, "text result", toolCallId = ToolCallId("b"), toolName = ToolName("read")),
            ),
        )

    @Test fun imageIsNestedInsideOriginalToolResult() {
        val encoder = AnthropicRequestEncoder(ImageResolver { ImagePayload.Base64("cGl4ZWxz") })
        val body = Json.parseToJsonElement(encoder.encode(request())).jsonObject
        val messages = body.getValue("messages").jsonArray
        assertEquals(3, messages.size)
        val results =
            messages
                .last()
                .jsonObject
                .getValue("content")
                .jsonArray
                .map { it.jsonObject }
        assertEquals(listOf("a", "b"), results.map { it.getValue("tool_use_id").jsonPrimitive.content })
        val parts = results[0].getValue("content").jsonArray
        assertEquals(
            listOf("text", "image"),
            parts.map {
                it.jsonObject
                    .getValue("type")
                    .jsonPrimitive.content
            },
        )
        assertEquals(
            "cGl4ZWxz",
            parts[1]
                .jsonObject
                .getValue("source")
                .jsonObject
                .getValue("data")
                .jsonPrimitive.content,
        )
        assertEquals("text result", results[1].getValue("content").jsonPrimitive.content)
    }

    @Test(expected = IllegalArgumentException::class)
    fun brokenImageAbortsTheRequest() {
        AnthropicRequestEncoder(ImageResolver { throw IllegalArgumentException("Unavailable") }).encode(request())
    }
}
