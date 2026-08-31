package com.helix.provider.openai.chat

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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiChatProviderTest {
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
        protocol: ProviderProtocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
        endpoint: String = "http://127.0.0.1:11434/v1",
    ): ProviderConfig =
        ProviderConfig(
            id = "p1",
            displayName = "Chat",
            protocol = protocol,
            endpoint = NormalizedEndpoint.parse(endpoint),
            model = "llama-test",
            headers = emptyMap(),
            secretAlias = SecretAlias("alias_1"),
            capabilitySnapshot = baseSnapshot,
        )

    private fun provider(
        wire: WireClient,
        config: ProviderConfig = config(),
    ): OpenAiChatProvider = OpenAiChatProvider(config, { "sekret" }, wire) { ImagePayload.Base64("aW1hZ2U=") }

    private fun userRequest(): ModelRequest =
        ModelRequest(
            "llama-test",
            listOf(ModelMessage(ModelRole.USER, "hi")),
            maxOutputTokens = 128L,
        )

    // --- SSE fixtures (same shapes as the HXA-023 decoder tests) -------------

    private fun chunk(json: String): String = "data: $json\n\n"

    private fun happyStream(): String =
        chunk(
            "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\"," +
                "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":null}," +
                "\"finish_reason\":null}]}",
        ) +
            chunk(
                "{\"id\":\"chatcmpl-1\",\"choices\":[{\"index\":0," +
                    "\"delta\":{\"content\":\"Hel\"},\"finish_reason\":null}]}",
            ) +
            chunk(
                "{\"id\":\"chatcmpl-1\",\"choices\":[{\"index\":0," +
                    "\"delta\":{\"content\":\"lo\"},\"finish_reason\":null}]}",
            ) +
            chunk(
                "{\"id\":\"chatcmpl-1\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}",
            ) +
            chunk(
                "{\"id\":\"chatcmpl-1\",\"choices\":[]," +
                    "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":3,\"total_tokens\":14}}",
            ) +
            "data: [DONE]\n\n"

    @Test
    fun wrongProtocolIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            OpenAiChatProvider(
                config(protocol = ProviderProtocol.OPENAI_RESPONSES),
                { "s" },
                FakeWire(WireResponse(200, emptyMap(), FakeBody(ByteArray(0)))),
            ) { ImagePayload.Base64("aW1hZ2U=") }
        }
    }

    @Test
    fun streamPostsToChatCompletionsWithBearerAuth() =
        runBlocking {
            val wire = FakeWire(WireResponse(200, emptyMap(), FakeBody(happyStream().toByteArray())))
            val events = provider(wire).stream(userRequest()).toList()
            // Chat Completions: the usage chunk is vendor-documented to arrive
            // after the finish chunk, so Completed precedes Usage here (unlike
            // the other two protocols).
            assertEquals(
                listOf(
                    ModelEvent.TextDelta("Hel"),
                    ModelEvent.TextDelta("lo"),
                    ModelEvent.Completed("stop"),
                    ModelEvent.Usage(11, 3),
                ),
                events,
            )
            val sent = wire.requests.single()
            assertEquals("http://127.0.0.1:11434/v1/chat/completions", sent.url)
            assertEquals("Bearer sekret", sent.headers["Authorization"])
            val body = String(sent.body!!)
            assertTrue(body.contains("\"model\":\"llama-test\""))
            assertTrue(body.contains("\"stream\":true"))
            assertTrue(body.contains("\"max_tokens\":128"))
        }

    @Test
    fun streamHttpErrorBecomesErrorTerminal() =
        runBlocking {
            val wire = FakeWire(WireResponse(401, emptyMap(), FakeBody("denied".toByteArray())))
            val events = provider(wire).stream(userRequest()).toList()
            assertEquals(listOf<ModelEvent>(ModelEvent.Error(ModelErrorCode.AUTH, false)), events)
        }

    @Test
    fun ollamaStyleEndpointKeepsThePort() =
        runBlocking {
            val wire = FakeWire(WireResponse(200, emptyMap(), FakeBody(happyStream().toByteArray())))
            provider(wire).stream(userRequest()).toList()
            assertEquals("http://127.0.0.1:11434/v1/chat/completions", wire.requests.single().url)
        }

    @Test
    fun modelListParsesIds() =
        runBlocking {
            val wire =
                FakeWire(
                    WireResponse(
                        200,
                        emptyMap(),
                        FakeBody("{\"data\":[{\"id\":\"llama-a\"},{\"id\":\"llama-b\"}]}".toByteArray()),
                    ),
                )
            assertEquals(
                ModelCatalogResult.Listed(listOf("llama-a", "llama-b")),
                provider(wire).listModels(),
            )
            assertEquals("http://127.0.0.1:11434/v1/models", wire.requests.single().url)
        }

    @Test
    fun validationPassesThroughModelList() =
        runBlocking {
            val wire =
                FakeWire(
                    WireResponse(
                        200,
                        emptyMap(),
                        FakeBody("{\"data\":[{\"id\":\"llama-a\"}]}".toByteArray()),
                    ),
                )
            assertEquals(ProviderCheckResult.Ok, provider(wire).validateConfiguration())
        }

    @Test
    fun validationWithoutModelsListUsesStream() {
        // The chat provider always has a /models endpoint; the stream fallback is
        // covered in WireModelProviderTest. Here: a 503 on /models must surface.
        val wire = FakeWire(WireResponse(503, emptyMap(), FakeBody("busy".toByteArray())))
        val result = runBlocking { provider(wire).validateConfiguration() }
        assertEquals(ProviderCheckResult.Failed::class, result::class)
        val failed = result as ProviderCheckResult.Failed
        assertEquals(ModelErrorCode.SERVER_ERROR, failed.code)
        assertEquals(true, failed.retryable)
    }
}
