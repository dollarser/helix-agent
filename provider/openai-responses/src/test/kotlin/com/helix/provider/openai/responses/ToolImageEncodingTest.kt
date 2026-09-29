package com.helix.provider.openai.responses

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
import org.junit.Assert.assertFalse
import org.junit.Test

class ToolImageEncodingTest {
    private val image = ImageReference(ArtifactRef("pixels"), "image/png")

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
                ModelMessage(ModelRole.TOOL, "image prepared", listOf(image), ToolCallId("a"), ToolName("view_image")),
                ModelMessage(ModelRole.TOOL, "text result", toolCallId = ToolCallId("b"), toolName = ToolName("read")),
            ),
        )

    @Test fun nativeOutputCarriesActualImageAndPreservesSiblingResults() {
        val body = ResponsesRequestEncoder(ImageResolver { ImagePayload.Base64("cGl4ZWxz") }).encode(request())
        val inputs =
            Json
                .parseToJsonElement(body)
                .jsonObject
                .getValue("input")
                .jsonArray
                .map { it.jsonObject }
        val outputs = inputs.filter { it["type"]?.jsonPrimitive?.content == "function_call_output" }
        assertEquals(listOf("a", "b"), outputs.map { it.getValue("call_id").jsonPrimitive.content })
        val parts = outputs[0].getValue("output").jsonArray
        assertEquals(
            listOf("input_text", "input_image"),
            parts.map {
                it.jsonObject
                    .getValue("type")
                    .jsonPrimitive.content
            },
        )
        assertEquals(
            "data:image/png;base64,cGl4ZWxz",
            parts[1]
                .jsonObject
                .getValue("image_url")
                .jsonPrimitive.content,
        )
        assertEquals("text result", outputs[1].getValue("output").jsonPrimitive.content)
        assertEquals(1, inputs.count { it["role"]?.jsonPrimitive?.content == "user" })
        assertFalse(request().messages.any { it.text.contains("cGl4ZWxz") })
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingPixelsAbortInsteadOfSilentlySendingTextOnly() {
        ResponsesRequestEncoder(ImageResolver { throw IllegalArgumentException("Unavailable") }).encode(request())
    }
}
