package com.helix.provider.openai.responses

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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test

class ResponsesRequestEncoderTest {
    private val resolver =
        ImageResolver { image ->
            when (image.ref.value) {
                "art.url" -> ImagePayload.Url("https://images.example.com/a.png")
                else -> ImagePayload.Base64("aW1hZ2U=")
            }
        }

    private val encoder = ResponsesRequestEncoder(resolver)

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

    private fun input(body: JsonObject): JsonArray = body["input"]?.jsonArray ?: error("missing input")

    private fun assertEnvelope(body: JsonObject) {
        assertEquals("gpt-test", str(body, "model"))
        assertEquals(true, body["stream"]?.jsonPrimitive?.boolean)
        assertEquals(4, input(body).size)
    }

    private fun assertSystemItem(item: JsonObject) {
        assertEquals("message", str(item, "type"))
        assertEquals("system", str(item, "role"))
        val content = item["content"]?.jsonArray ?: error("missing content")
        assertEquals("input_text", str(content[0].jsonObject, "type"))
        assertEquals("be concise", str(content[0].jsonObject, "text"))
    }

    private fun assertUserItem(item: JsonObject) {
        val content = item["content"]?.jsonArray ?: error("missing user content")
        assertEquals(3, content.size)
        assertEquals("input_text", str(content[0].jsonObject, "type"))
        assertEquals("input_image", str(content[1].jsonObject, "type"))
        assertEquals("data:image/png;base64,aW1hZ2U=", str(content[1].jsonObject, "image_url"))
        assertEquals("input_image", str(content[2].jsonObject, "type"))
        assertEquals("https://images.example.com/a.png", str(content[2].jsonObject, "image_url"))
    }

    private fun assertAssistantItem(item: JsonObject) {
        val part = item["content"]?.jsonArray?.get(0)?.jsonObject ?: error("no assistant content")
        assertEquals("output_text", str(part, "type"))
    }

    private fun assertToolItem(item: JsonObject) {
        assertEquals("function_call_output", str(item, "type"))
        assertEquals("call_1", str(item, "call_id"))
        assertEquals("result", str(item, "output"))
    }

    private fun assertToolsSection(body: JsonObject) {
        val tools = body["tools"]?.jsonArray ?: error("missing tools")
        assertEquals(1, tools.size)
        val tool = tools[0].jsonObject
        assertEquals("function", str(tool, "type"))
        assertEquals("read", str(tool, "name"))
        assertEquals("read a file", str(tool, "description"))
        val parameters = tool["parameters"]?.jsonObject ?: error("missing parameters")
        assertEquals("object", str(parameters, "type"))
    }

    private fun assertSamplingSection(body: JsonObject) {
        assertEquals(0.5, body["temperature"]?.jsonPrimitive?.double)
        assertEquals(100L, body["max_output_tokens"]?.jsonPrimitive?.long)
        assertEquals(7L, body["seed"]?.jsonPrimitive?.long)
        val stop = body["stop"]?.jsonArray ?: error("missing stop")
        assertEquals(1, stop.size)
        assertEquals("END", stop[0].jsonPrimitive.content)
        val reasoning = body["reasoning"]?.jsonObject ?: error("missing reasoning")
        assertEquals("high", str(reasoning, "effort"))
    }

    @Test
    fun encodesFullRequest() {
        val body = parsed(encoder.encode(fullRequest()))
        assertEnvelope(body)
        val items = input(body)
        assertSystemItem(items[0].jsonObject)
        assertUserItem(items[1].jsonObject)
        assertAssistantItem(items[2].jsonObject)
        assertToolItem(items[3].jsonObject)
        assertToolsSection(body)
        assertSamplingSection(body)
    }

    @Test
    fun omitsOptionalFieldsWhenAbsent() {
        val request =
            ModelRequest(
                model = "m",
                messages = listOf(ModelMessage(ModelRole.USER, "hi")),
            )
        val body = parsed(encoder.encode(request))
        assertEquals(null, body["temperature"])
        assertEquals(null, body["max_output_tokens"])
        assertEquals(null, body["seed"])
        assertEquals(null, body["stop"])
        assertEquals(null, body["reasoning"])
        assertEquals(null, body["tools"])
        val input = body["input"]?.jsonArray ?: error("missing input")
        assertEquals("user", str(input[0].jsonObject, "role"))
        val content = input[0].jsonObject["content"]?.jsonArray ?: error("missing content")
        assertEquals(1, content.size)
    }

    @Test
    fun reasoningOffOmitsReasoningBlock() {
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
        assertEquals(null, body["reasoning"])
    }

    @Test
    fun inputOrderIsPreserved() {
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
        val input = parsed(encoder.encode(request))["input"]?.jsonArray ?: error("missing input")
        val roles =
            input.map { item ->
                val obj = item.jsonObject
                if (str(obj, "type") == "function_call_output") "tool" else str(obj, "role")
            }
        assertEquals(listOf("system", "user", "assistant", "user"), roles)
        val texts =
            input.map { item ->
                item.jsonObject["content"]
                    ?.jsonArray
                    ?.firstOrNull()
                    ?.jsonObject
                    ?.get("text")
                    ?.jsonPrimitive
                    ?.content
            }
        assertEquals(listOf("s", "u1", "a", "u2"), texts)
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
        val tool = parsed(encoder.encode(request))["tools"]?.jsonArray?.get(0)?.jsonObject ?: error("no tool")
        val parameters = tool["parameters"]?.jsonObject ?: error("no parameters")
        // The canonical schema text round-trips structurally (same object shape).
        assertEquals(
            parsed(schema),
            parameters,
        )
        assertEquals("t.a", str(tool, "name"))
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
                ResponsesRequestEncoder(throwing).encode(request)
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
            ResponsesRequestEncoder(badUrl).encode(request)
        }
    }
}
