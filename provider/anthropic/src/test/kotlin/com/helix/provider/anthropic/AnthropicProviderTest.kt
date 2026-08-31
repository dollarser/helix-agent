package com.helix.provider.anthropic

import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SecretAlias
import com.helix.provider.api.CapabilitySource
import com.helix.provider.api.ModelCatalogResult
import com.helix.provider.api.ProviderCapabilities
import com.helix.provider.api.ProviderCheckResult
import com.helix.provider.api.ProviderConfig
import com.helix.provider.api.wire.WireBody
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicProviderTest {
    private class FakeBody(
        bytes: ByteArray,
    ) : WireBody {
        private val data = bytes

        override suspend fun bytes(): ByteArray = data.copyOf()

        override suspend fun forEachChunk(onChunk: suspend (ByteArray) -> Boolean) {
            if (data.isNotEmpty() && !onChunk(data.copyOf())) return
        }

        override fun close() = Unit
    }

    private class FakeWire(
        private val response: WireResponse,
    ) : WireClient {
        val requests = ArrayList<WireRequest>()

        override suspend fun open(request: WireRequest): WireResponse {
            requests += request
            return response
        }
    }

    private val baseSnapshot =
        ProviderCapabilities.toJsonString(
            ProviderCapabilities(
                true,
                true,
                false,
                false,
                false,
                false,
                null,
                CapabilitySource.PROBED,
            ),
        )

    private fun config(
        protocol: ProviderProtocol = ProviderProtocol.ANTHROPIC_MESSAGES,
        endpoint: String = "https://api.anthropic.com",
    ): ProviderConfig =
        ProviderConfig(
            id = "p1",
            displayName = "Anth",
            protocol = protocol,
            endpoint = NormalizedEndpoint.parse(endpoint),
            model = "claude-test",
            headers = emptyMap(),
            secretAlias = SecretAlias("alias_1"),
            capabilitySnapshot = baseSnapshot,
        )

    private fun provider(
        wire: WireClient,
        config: ProviderConfig = config(),
    ): AnthropicProvider = AnthropicProvider(config, { "sekret" }, wire) { ImagePayload.Base64("aW1hZ2U=") }

    private fun userRequest(): ModelRequest =
        ModelRequest(
            "claude-test",
            listOf(ModelMessage(ModelRole.USER, "hi")),
        )

    // --- SSE fixtures (same shapes as the HXA-024 decoder tests) -------------

    private fun sse(
        type: String,
        json: String,
    ): String = "event: $type\ndata: $json\n\n"

    private fun happyStream(): String =
        sse(
            "message_start",
            "{\"type\":\"message_start\",\"message\":{\"id\":\"m1\",\"type\":\"message\"," +
                "\"role\":\"assistant\",\"model\":\"claude\",\"content\":[],\"stop_reason\":null," +
                "\"usage\":{\"input_tokens\":25,\"output_tokens\":1}}}",
        ) +
            sse(
                "content_block_start",
                "{\"type\":\"content_block_start\",\"index\":0," +
                    "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
            ) +
            sse(
                "content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":0," +
                    "\"delta\":{\"type\":\"text_delta\",\"text\":\"Hel\"}}",
            ) +
            sse(
                "content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":0," +
                    "\"delta\":{\"type\":\"text_delta\",\"text\":\"lo\"}}",
            ) +
            sse("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}") +
            sse(
                "message_delta",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"," +
                    "\"stop_sequence\":null},\"usage\":{\"output_tokens\":35}}",
            ) +
            sse("message_stop", "{\"type\":\"message_stop\"}")

    @Test
    fun wrongProtocolIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            AnthropicProvider(
                config(protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS),
                { "s" },
                FakeWire(WireResponse(200, emptyMap(), FakeBody(ByteArray(0)))),
            ) { ImagePayload.Base64("aW1hZ2U=") }
        }
    }

    @Test
    fun streamPostsToMessagesWithApiKeyHeader() =
        runBlocking {
            val wire = FakeWire(WireResponse(200, emptyMap(), FakeBody(happyStream().toByteArray())))
            val events = provider(wire).stream(userRequest()).toList()
            assertEquals(
                listOf(
                    ModelEvent.TextDelta("Hel"),
                    ModelEvent.TextDelta("lo"),
                    ModelEvent.Usage(25, 35),
                    ModelEvent.Completed("stop"),
                ),
                events,
            )
            val sent = wire.requests.single()
            assertEquals("https://api.anthropic.com/messages", sent.url)
            assertEquals("sekret", sent.headers["x-api-key"])
            assertEquals("2023-06-01", sent.headers["anthropic-version"])
            assertNull(sent.headers["Authorization"])
            val body = String(sent.body!!)
            assertTrue(body.contains("\"model\":\"claude-test\""))
            assertTrue(body.contains("\"max_tokens\""))
        }

    @Test
    fun streamHttpErrorBecomesErrorTerminal() =
        runBlocking {
            val wire = FakeWire(WireResponse(401, emptyMap(), FakeBody("denied".toByteArray())))
            val events = provider(wire).stream(userRequest()).toList()
            assertEquals(listOf<ModelEvent>(ModelEvent.Error(ModelErrorCode.AUTH, false)), events)
        }

    @Test
    fun modelListIsUnsupported() {
        val wire = FakeWire(WireResponse(200, emptyMap(), FakeBody(ByteArray(0))))
        val result = runBlocking { provider(wire).listModels() }
        assertEquals(ModelCatalogResult.Unsupported, result)
        assertEquals(0, wire.requests.size)
    }

    @Test
    fun validationWithoutModelsUsesMinimalStream() =
        runBlocking {
            val wire = FakeWire(WireResponse(200, emptyMap(), FakeBody(happyStream().toByteArray())))
            val result = provider(wire).validateConfiguration()
            assertEquals(ProviderCheckResult.Ok, result)
            assertEquals("https://api.anthropic.com/messages", wire.requests.single().url)
        }

    @Test
    fun validationStreamErrorSurfaces() =
        runBlocking {
            val wire = FakeWire(WireResponse(401, emptyMap(), FakeBody("denied".toByteArray())))
            val result = provider(wire).validateConfiguration()
            assertEquals(ProviderCheckResult.Failed::class, result::class)
            val failed = result as ProviderCheckResult.Failed
            assertEquals(ModelErrorCode.AUTH, failed.code)
            assertEquals(false, failed.retryable)
        }

    @Test
    fun userHeadersJoinTheRequest() =
        runBlocking {
            val cfg =
                config().copy(headers = mapOf("anthropic-beta" to "tools-2024"))
            val wire = FakeWire(WireResponse(200, emptyMap(), FakeBody(happyStream().toByteArray())))
            provider(wire, cfg).stream(userRequest()).toList()
            assertEquals("tools-2024", wire.requests.single().headers["anthropic-beta"])
        }
}
