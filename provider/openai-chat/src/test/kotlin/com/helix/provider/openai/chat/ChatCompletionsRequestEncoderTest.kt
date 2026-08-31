package com.helix.provider.openai.chat

import com.helix.core.model.ArtifactRef
import com.helix.core.model.ImageReference
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.ModelToolSchema
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.ToolCallId
import com.helix.core.model.ToolName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatCompletionsRequestEncoderTest {
    private val resolver =
        ImageResolver { image ->
            when (image.ref.value) {
                "art.url" -> ImagePayload.Url("https://images.example.com/a.png")
                else -> ImagePayload.Base64("aW1hZ2U=")
            }
        }

    private val encoder = ChatCompletionsRequestEncoder(resolver)

    private fun parsed(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    private fun str(
        obj: JsonObject,
        key: String,
    ): String = obj[key]?.jsonPrimitive?.content ?: error("missing $key")

    private fun fullRequest(): ModelRequest =
        ModelRequest(
            model = "gpt-test",
            messages =
                listOf(
                    ModelMessage(ModelRole.SYSTEM, "be concise"),
                    ModelMessage(
                        ModelRole.USER,
                        "look",
                        images =
                            listOf(
                                ImageReference(ArtifactRef("art.data"), "image/png"),
                                ImageReference(ArtifactRef("art.url"), "image/jpeg"),
                            ),
                    ),
                    ModelMessage(ModelRole.ASSISTANT, "ok"),
                    ModelMessage(
                        ModelRole.TOOL,
                        "result",
                        toolCallId = ToolCallId("call_1"),
                        toolName = ToolName("read"),
                    ),
                ),
            tools =
                listOf(
                    ModelToolSchema(
                        ToolName("read"),
                        "read a file",
                        """{"type":"object","properties":{"path":{"type":"string"}}}""",
                    ),
                ),
            temperature = 0.5,
            maxOutputTokens = 100,
            seed = 7,
            stopSequences = listOf("END"),
            reasoning = ReasoningEffort.HIGH,
        )

    private fun messages(body: JsonObject): JsonArray =
        body["messages"]?.jsonArray
            ?: error("missing messages")

    private fun assertEnvelope(body: JsonObject) {
        assertEquals("gpt-test", str(body, "model"))
        assertEquals(true, body["stream"]?.jsonPrimitive?.boolean)
        val streamOptions = body["stream_options"]?.jsonObject ?: error("missing stream_options")
        assertEquals(true, streamOptions["include_usage"]?.jsonPrimitive?.boolean)
        assertEquals(4, messages(body).size)
    }

    private fun assertSystemMessage(message: JsonObject) {
        assertEquals("system", str(message, "role"))
        assertEquals("be concise", str(message, "content"))
    }

    private fun assertUserMessage(message: JsonObject) {
        assertEquals("user", str(message, "role"))
        val content = message["content"]?.jsonArray ?: error("missing user content")
        assertEquals(3, content.size)
        assertEquals("text", str(content[0].jsonObject, "type"))
        assertEquals("look", str(content[0].jsonObject, "text"))
        assertEquals("image_url", str(content[1].jsonObject, "type"))
        val dataUrl = content[1].jsonObject["image_url"]?.jsonObject ?: error("no image_url")
        assertEquals("data:image/png;base64,aW1hZ2U=", str(dataUrl, "url"))
        assertEquals("image_url", str(content[2].jsonObject, "type"))
        val publicUrl = content[2].jsonObject["image_url"]?.jsonObject ?: error("no image_url")
        assertEquals("https://images.example.com/a.png", str(publicUrl, "url"))
    }

    private fun assertAssistantMessage(message: JsonObject) {
        assertEquals("assistant", str(message, "role"))
        assertEquals("ok", str(message, "content"))
    }

    private fun assertToolMessage(message: JsonObject) {
        assertEquals("tool", str(message, "role"))
        assertEquals("call_1", str(message, "tool_call_id"))
        assertEquals("result", str(message, "content"))
    }

    private fun assertToolsSection(body: JsonObject) {
        val tools = body["tools"]?.jsonArray ?: error("missing tools")
        assertEquals(1, tools.size)
        val tool = tools[0].jsonObject
        assertEquals("function", str(tool, "type"))
        val function = tool["function"]?.jsonObject ?: error("missing function block")
        assertEquals("read", str(function, "name"))
        assertEquals("read a file", str(function, "description"))
        val parameters = function["parameters"]?.jsonObject ?: error("missing parameters")
        assertEquals("object", str(parameters, "type"))
    }

    private fun assertSamplingSection(body: JsonObject) {
        assertEquals(0.5, body["temperature"]?.jsonPrimitive?.double)
        assertEquals(100L, body["max_tokens"]?.jsonPrimitive?.long)
        assertEquals(7L, body["seed"]?.jsonPrimitive?.long)
        val stop = body["stop"]?.jsonArray ?: error("missing stop")
        assertEquals(1, stop.size)
        assertEquals("END", stop[0].jsonPrimitive.content)
        assertEquals("high", str(body, "reasoning_effort"))
    }

    @Test
    fun encodesFullRequest() {
        val body = parsed(encoder.encode(fullRequest()))
        assertEnvelope(body)
        val items = messages(body)
        assertSystemMessage(items[0].jsonObject)
        assertUserMessage(items[1].jsonObject)
        assertAssistantMessage(items[2].jsonObject)
        assertToolMessage(items[3].jsonObject)
        assertToolsSection(body)
        assertSamplingSection(body)
    }

    @Test
    fun omitsOptionalFieldsWhenAbsent() {
        val body =
            parsed(
                encoder.encode(
                    ModelRequest(
                        model = "m",
                        messages = listOf(ModelMessage(ModelRole.USER, "hi")),
                    ),
                ),
            )
        assertEquals(null, body["temperature"])
        assertEquals(null, body["max_tokens"])
        assertEquals(null, body["seed"])
        assertEquals(null, body["stop"])
        assertEquals(null, body["reasoning_effort"])
        assertEquals(null, body["tools"])
        val message = messages(body)[0].jsonObject
        assertEquals("user", str(message, "role"))
        assertEquals("hi", str(message, "content"))
    }

    @Test
    fun reasoningOffOmitsReasoningEffort() {
        val body =
            parsed(
                encoder.encode(
                    ModelRequest(
                        model = "m",
                        messages = listOf(ModelMessage(ModelRole.USER, "hi")),
                        reasoning = ReasoningEffort.OFF,
                    ),
                ),
            )
        assertEquals(null, body["reasoning_effort"])
    }

    @Test
    fun messageOrderIsPreserved() {
        val request =
            ModelRequest(
                model = "m",
                messages =
                    listOf(
                        ModelMessage(ModelRole.SYSTEM, "s"),
                        ModelMessage(ModelRole.USER, "u1"),
                        ModelMessage(ModelRole.ASSISTANT, "a"),
                        ModelMessage(ModelRole.USER, "u2"),
                    ),
            )
        val items = messages(parsed(encoder.encode(request)))
        assertEquals(
            listOf("system", "user", "assistant", "user"),
            items.map { str(it.jsonObject, "role") },
        )
        assertEquals(
            listOf("s", "u1", "a", "u2"),
            items.map { str(it.jsonObject, "content") },
        )
    }

    @Test
    fun toolSchemaIsEmbeddedVerbatim() {
        val schema = """{"type":"object","required":["a"],"properties":{"a":{"type":"integer"}}}"""
        val request =
            ModelRequest(
                model = "m",
                messages = listOf(ModelMessage(ModelRole.USER, "hi")),
                tools = listOf(ModelToolSchema(ToolName("t.a"), "d", schema)),
            )
        val tool =
            parsed(encoder.encode(request))["tools"]?.jsonArray?.get(0)?.jsonObject
                ?: error("no tool")
        val function = tool["function"]?.jsonObject ?: error("no function block")
        val parameters = function["parameters"]?.jsonObject ?: error("no parameters")
        // The canonical schema text round-trips structurally (same object shape).
        assertEquals(parsed(schema), parameters)
        assertEquals("t.a", str(function, "name"))
    }

    @Test
    fun unresolvedImageFailsClosed() {
        val throwing =
            ImageResolver {
                throw IllegalArgumentException("content store miss")
            }
        val request =
            ModelRequest(
                model = "m",
                messages =
                    listOf(
                        ModelMessage(
                            ModelRole.USER,
                            "look",
                            images = listOf(ImageReference(ArtifactRef("art.gone"), "image/png")),
                        ),
                    ),
            )
        val ex =
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                ChatCompletionsRequestEncoder(throwing).encode(request)
            }
        assertEquals("content store miss", ex.message)
    }

    @Test
    fun imageUrlWithControlCharacterIsRejected() {
        val badUrl =
            ImageResolver { ImagePayload.Url("https://x\u0001example.com/a.png") }
        val request =
            ModelRequest(
                model = "m",
                messages =
                    listOf(
                        ModelMessage(
                            ModelRole.USER,
                            "look",
                            images = listOf(ImageReference(ArtifactRef("art.u"), "image/png")),
                        ),
                    ),
            )
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            ChatCompletionsRequestEncoder(badUrl).encode(request)
        }
    }
}
