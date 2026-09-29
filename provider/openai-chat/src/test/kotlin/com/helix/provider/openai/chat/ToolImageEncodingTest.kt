package com.helix.provider.openai.chat

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
import org.junit.Assert.assertTrue
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

    @Test fun syntheticImageFollowsAllToolResultsAndDoesNotMutateHistory() {
        val original = request()
        val body = ChatCompletionsRequestEncoder(ImageResolver { ImagePayload.Base64("cGl4ZWxz") }).encode(original)
        val messages =
            Json
                .parseToJsonElement(body)
                .jsonObject
                .getValue("messages")
                .jsonArray
                .map { it.jsonObject }
        assertEquals(
            listOf("user", "assistant", "tool", "tool", "user"),
            messages.map {
                it.getValue("role").jsonPrimitive.content
            },
        )
        assertEquals("a", messages[2].getValue("tool_call_id").jsonPrimitive.content)
        assertEquals("b", messages[3].getValue("tool_call_id").jsonPrimitive.content)
        val parts = messages.last().getValue("content").jsonArray
        assertTrue(
            parts[0]
                .jsonObject
                .getValue("text")
                .jsonPrimitive.content
                .contains("UNTRUSTED TOOL IMAGE"),
        )
        assertEquals(
            "data:image/png;base64,cGl4ZWxz",
            parts[1]
                .jsonObject
                .getValue("image_url")
                .jsonObject
                .getValue("url")
                .jsonPrimitive.content,
        )
        assertEquals(4, original.messages.size)
        assertEquals(1, original.messages.count { it.role == ModelRole.USER })
    }

    @Test(expected = IllegalArgumentException::class)
    fun imageResolverFailureCannotFallbackToText() {
        ChatCompletionsRequestEncoder(ImageResolver { throw IllegalArgumentException("Unavailable") }).encode(request())
    }
}
