package com.helix.provider.anthropic

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Request encoder tests (HXA-024): wire shape, the tool-result ordering
 * constraint (strict role alternation, merged tool runs), max_tokens
 * mandate, thinking budgets, fail-closed paths.
 */
class AnthropicRequestEncoderTest {
    private val resolver =
        ImageResolver { image ->
            when (image.ref.value) {
                "art.url" -> ImagePayload.Url("https://images.example.com/a.png")
                else -> ImagePayload.Base64("aW1hZ2U=")
            }
        }

    private val encoder = AnthropicRequestEncoder(resolver)

    private fun parsed(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    private fun str(
        obj: JsonObject,
        key: String,
    ): String = obj[key]?.jsonPrimitive?.content ?: error("missing $key")

    private fun o(
        obj: JsonObject,
        key: String,
    ): JsonObject = obj[key]!!.jsonObject

    private fun arr(
        obj: JsonObject,
        key: String,
    ): JsonArray = obj[key]!!.jsonArray

    private fun user(text: String) = ModelMessage(ModelRole.USER, text)

    private fun assistant(text: String) = ModelMessage(ModelRole.ASSISTANT, text)

    private fun tool(
        id: String,
        name: String,
        text: String,
    ) = ModelMessage(ModelRole.TOOL, text, emptyList(), ToolCallId(id), ToolName(name))

    private fun schema(
        name: String,
        schemaJson: String,
    ) = ModelToolSchema(ToolName(name), "Desc $name", schemaJson)

    @Test
    fun fullRequest() {
        val body = parsed(encoder.encode(fullRequestModel()))
        assertEnvelope(body)
        // Merged tool run: wire = user(images), assistant, user(tool_results).
        val messages = arr(body, "messages")
        assertEquals(3, messages.size)
        assertUserImageMessage(messages[0].jsonObject)
        assertAssistantAndToolRuns(messages)
        assertToolsSection(body)
    }

    private fun fullRequestModel() =
        ModelRequest(
            model = "claude-test",
            messages =
                listOf(
                    ModelMessage(ModelRole.SYSTEM, "You are Helix."),
                    ModelMessage(ModelRole.SYSTEM, "Be brief."),
                    ModelMessage(
                        ModelRole.USER,
                        "Look",
                        listOf(
                            ImageReference(ArtifactRef("art.b64"), "image/png"),
                            ImageReference(ArtifactRef("art.url"), "image/jpeg"),
                        ),
                    ),
                    assistant("I will read."),
                    tool("toolu_a", "read", "file content"),
                    tool("toolu_b", "write", "written"),
                ),
            tools =
                listOf(
                    schema("read", "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}"),
                    schema("write", "{\"type\":\"object\"}"),
                ),
            temperature = 0.5,
            maxOutputTokens = 10_000,
            stopSequences = listOf("END"),
            seed = 42,
            reasoning = ReasoningEffort.MEDIUM,
        )

    private fun assertEnvelope(body: JsonObject) {
        assertEquals("claude-test", str(body, "model"))
        assertEquals(10_000L, body["max_tokens"]?.jsonPrimitive?.long)
        // MEDIUM budget 4096 fits under 10000-1024: sent as-is.
        assertEquals(4096L, o(body, "thinking")["budget_tokens"]?.jsonPrimitive?.long)
        // Two system messages join into the single top-level field.
        assertEquals("You are Helix.\n\nBe brief.", str(body, "system"))
        assertEquals(true, body["stream"]?.jsonPrimitive?.boolean)
        assertEquals(0.5, body["temperature"]?.jsonPrimitive?.double)
        assertEquals("END", arr(body, "stop_sequences")[0].jsonPrimitive.content)
    }

    private fun assertUserImageMessage(message: JsonObject) {
        assertEquals("user", str(message, "role"))
        // USER with images: text block first, then the two image blocks.
        val content = message["content"] as JsonArray
        assertEquals(3, content.size)
        assertEquals("text", str(content[0].jsonObject, "type"))
        assertEquals("Look", str(content[0].jsonObject, "text"))
        assertEquals("image", str(content[1].jsonObject, "type"))
        val b64Source = o(content[1].jsonObject, "source")
        assertEquals("base64", str(b64Source, "type"))
        assertEquals("image/png", str(b64Source, "media_type"))
        assertEquals("aW1hZ2U=", str(b64Source, "data"))
        val urlSource = o(content[2].jsonObject, "source")
        assertEquals("url", str(urlSource, "type"))
        assertEquals("https://images.example.com/a.png", str(urlSource, "url"))
    }

    private fun assertAssistantAndToolRuns(messages: JsonArray) {
        assertEquals("assistant", str(messages[1].jsonObject, "role"))
        assertEquals("I will read.", str(messages[1].jsonObject, "content"))
        // The tool run is ONE user message with both results, in order.
        assertEquals("user", str(messages[2].jsonObject, "role"))
        val toolContent = messages[2].jsonObject["content"] as JsonArray
        assertEquals(2, toolContent.size)
        assertEquals("tool_result", str(toolContent[0].jsonObject, "type"))
        assertEquals("toolu_a", str(toolContent[0].jsonObject, "tool_use_id"))
        assertEquals("file content", str(toolContent[0].jsonObject, "content"))
        assertEquals("toolu_b", str(toolContent[1].jsonObject, "tool_use_id"))
    }

    private fun assertToolsSection(body: JsonObject) {
        // Tools use the flat input_schema field.
        val tools = arr(body, "tools")
        assertEquals(2, tools.size)
        assertEquals("read", str(tools[0].jsonObject, "name"))
        assertEquals(
            Json.parseToJsonElement(
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}",
            ),
            tools[0].jsonObject["input_schema"],
        )
    }

    @Test
    fun fullRequestWithFeasibleThinking() {
        val request =
            ModelRequest(
                model = "claude-test",
                messages = listOf(user("Hi")),
                reasoning = ReasoningEffort.MEDIUM,
                maxOutputTokens = 10_000,
            )
        val body = parsed(encoder.encode(request))
        val thinking = o(body, "thinking")
        assertEquals("enabled", str(thinking, "type"))
        // MEDIUM budget 4096 fits under 10000-1024: sent as-is.
        assertEquals(4096L, thinking["budget_tokens"]?.jsonPrimitive?.long)
        assertEquals(10_000L, body["max_tokens"]?.jsonPrimitive?.long)
    }

    @Test
    fun highThinkingClampedToMaxTokens() {
        val request =
            ModelRequest(
                model = "claude-test",
                messages = listOf(user("Hi")),
                reasoning = ReasoningEffort.HIGH,
                maxOutputTokens = 8_192,
            )
        val body = parsed(encoder.encode(request))
        // HIGH prefers 16384 but max_tokens 8192 leaves 8192-1024 = 7168.
        assertEquals(7168L, o(body, "thinking")["budget_tokens"]?.jsonPrimitive?.long)
    }

    @Test
    fun infeasibleThinkingFailsClosed() {
        val request =
            ModelRequest(
                model = "claude-test",
                messages = listOf(user("Hi")),
                reasoning = ReasoningEffort.LOW,
                maxOutputTokens = 1_024,
            )
        assertThrows(IllegalArgumentException::class.java) { encoder.encode(request) }
    }

    @Test
    fun lowThinkingWithSmallMaxTokensFailsClosed() {
        // max_tokens 2000 leaves 976 < the 1024 minimum thinking budget.
        val request =
            ModelRequest(
                model = "claude-test",
                messages = listOf(user("Hi")),
                reasoning = ReasoningEffort.LOW,
                maxOutputTokens = 2_000,
            )
        assertThrows(IllegalArgumentException::class.java) { encoder.encode(request) }
    }

    @Test
    fun omitsOptionalFieldsWhenAbsent() {
        val body = parsed(encoder.encode(ModelRequest("claude-test", listOf(user("Hi")))))
        assertEquals("claude-test", str(body, "model"))
        // max_tokens is mandatory on the wire: the default is sent.
        assertEquals(8192L, body["max_tokens"]?.jsonPrimitive?.long)
        assertEquals(true, body["stream"]?.jsonPrimitive?.boolean)
        assertFalse(body.containsKey("system"))
        assertFalse(body.containsKey("tools"))
        assertFalse(body.containsKey("temperature"))
        assertFalse(body.containsKey("stop_sequences"))
        assertFalse(body.containsKey("thinking"))
        // seed has no Messages-API parameter: never sent.
        assertFalse(body.containsKey("seed"))
        assertEquals("Hi", str(arr(body, "messages")[0].jsonObject, "content"))
    }

    @Test
    fun seedNeverSent() {
        val request = ModelRequest("claude-test", listOf(user("Hi")), seed = 42)
        assertFalse("seed" in parsed(encoder.encode(request)))
    }

    @Test
    fun firstMessageMustBeUser() {
        val request = ModelRequest("claude-test", listOf(assistant("Hello"), user("Hi")))
        val e = assertThrows(IllegalArgumentException::class.java) { encoder.encode(request) }
        assertTrue(e.message!!.contains("first message"))
    }

    @Test
    fun consecutiveUserMessagesRejected() {
        val request = ModelRequest("claude-test", listOf(user("One"), user("Two")))
        val e = assertThrows(IllegalArgumentException::class.java) { encoder.encode(request) }
        assertTrue(e.message!!.contains("alternation"))
    }

    @Test
    fun toolResultWithoutAssistantTurnRejected() {
        // A tool result must answer the preceding assistant turn.
        val request = ModelRequest("claude-test", listOf(user("Look"), tool("toolu_1", "read", "x")))
        val e = assertThrows(IllegalArgumentException::class.java) { encoder.encode(request) }
        assertTrue(e.message!!.contains("alternation"))
    }

    @Test
    fun userTurnAfterToolRunRejected() {
        // After the merged tool run (a user turn), another user turn violates
        // alternation: the assistant reply must come first (next model call).
        val request =
            ModelRequest(
                "claude-test",
                listOf(user("Look"), assistant("Reading"), tool("toolu_1", "read", "x"), user("More")),
            )
        assertThrows(IllegalArgumentException::class.java) { encoder.encode(request) }
    }

    @Test
    fun threeToolResultsKeepOriginalOrder() {
        val request =
            ModelRequest(
                "claude-test",
                listOf(
                    user("Look"),
                    assistant("Three calls"),
                    tool("toolu_1", "read", "r1"),
                    tool("toolu_2", "read", "r2"),
                    tool("toolu_3", "write", "r3"),
                ),
            )
        val messages = arr(parsed(encoder.encode(request)), "messages")
        assertEquals(3, messages.size)
        val content = arr(messages[2].jsonObject, "content")
        assertEquals(3, content.size)
        assertEquals(listOf("toolu_1", "toolu_2", "toolu_3"), content.map { str(it.jsonObject, "tool_use_id") })
        assertEquals(listOf("r1", "r2", "r3"), content.map { str(it.jsonObject, "content") })
    }

    @Test
    fun toolSchemaRejectedWhenNotObject() {
        val e =
            assertThrows(IllegalArgumentException::class.java) {
                ModelToolSchema(ToolName("bad"), "d", "[1,2]")
            }
        assertTrue(e.message!!.contains("JSON object"))
    }

    @Test
    fun unresolvedImageFailsClosed() {
        val failing = ImageResolver { throw IllegalArgumentException("content store miss") }
        val encoder = AnthropicRequestEncoder(failing)
        val request =
            ModelRequest(
                "claude-test",
                listOf(
                    ModelMessage(
                        ModelRole.USER,
                        "Look",
                        listOf(ImageReference(ArtifactRef("art"), "image/png")),
                    ),
                ),
            )
        val e = assertThrows(IllegalArgumentException::class.java) { encoder.encode(request) }
        assertTrue(e.message!!.contains("content store miss"))
    }

    @Test
    fun imageUrlWithControlCharacterIsRejected() {
        val resolver = ImageResolver { ImagePayload.Url("https://a\nb") }
        val encoder = AnthropicRequestEncoder(resolver)
        val request =
            ModelRequest(
                "claude-test",
                listOf(
                    ModelMessage(
                        ModelRole.USER,
                        "Look",
                        listOf(ImageReference(ArtifactRef("art"), "image/png")),
                    ),
                ),
            )
        assertThrows(IllegalArgumentException::class.java) { encoder.encode(request) }
    }
}
