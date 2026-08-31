package com.helix.provider.openai.responses

import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SecretAlias
import com.helix.provider.api.CapabilitySource
import com.helix.provider.api.CredentialLookup
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

class OpenAiResponsesProviderTest {
    // --- fakes ---------------------------------------------------------------

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

    private fun config(protocol: ProviderProtocol = ProviderProtocol.OPENAI_RESPONSES): ProviderConfig =
        ProviderConfig(
            id = "p1",
            displayName = "Resp",
            protocol = protocol,
            endpoint = NormalizedEndpoint.parse("https://api.openai.com/v1"),
            model = "gpt-test",
            headers = emptyMap(),
            secretAlias = SecretAlias("alias_1"),
            capabilitySnapshot = baseSnapshot,
        )

    private fun provider(
        wire: WireClient,
        credentials: CredentialLookup = { "sekret" },
    ): OpenAiResponsesProvider =
        OpenAiResponsesProvider(config(), credentials, wire) { ImagePayload.Base64("aW1hZ2U=") }

    private fun userRequest(): ModelRequest =
        ModelRequest(
            "gpt-test",
            listOf(ModelMessage(ModelRole.USER, "hi")),
        )

    // --- SSE fixtures (same shapes as the HXA-022 decoder tests) -------------

    private fun sse(
        event: String,
        json: String,
    ): String = "event: $event\ndata: $json\n\n"

    private fun createdJson(seq: Int): String = "{\"type\":\"response.created\",\"sequence_number\":$seq}"

    private fun inProgressJson(seq: Int): String = "{\"type\":\"response.in_progress\",\"sequence_number\":$seq}"

    private fun messageItemAddedJson(seq: Int): String =
        "{\"type\":\"response.output_item.added\",\"sequence_number\":$seq,\"output_index\":0," +
            "\"item\":{\"id\":\"msg_1\",\"type\":\"message\",\"status\":\"in_progress\"," +
            "\"role\":\"assistant\",\"content\":[]}}"

    private fun contentPartAddedJson(seq: Int): String =
        "{\"type\":\"response.content_part.added\",\"sequence_number\":$seq," +
            "\"item_id\":\"msg_1\",\"output_index\":0,\"content_index\":0}"

    private fun textDeltaJson(
        seq: Int,
        delta: String,
    ): String =
        "{\"type\":\"response.output_text.delta\",\"sequence_number\":$seq," +
            "\"item_id\":\"msg_1\",\"output_index\":0,\"content_index\":0,\"delta\":\"$delta\"}"

    private fun textDoneJson(seq: Int): String =
        "{\"type\":\"response.output_text.done\",\"sequence_number\":$seq," +
            "\"item_id\":\"msg_1\",\"output_index\":0,\"content_index\":0,\"text\":\"final\"}"

    private fun messageItemDoneJson(
        seq: Int,
        status: String,
    ): String =
        "{\"type\":\"response.output_item.done\",\"sequence_number\":$seq,\"output_index\":0," +
            "\"item\":{\"id\":\"msg_1\",\"type\":\"message\",\"status\":\"$status\"}}"

    private fun completedJson(
        seq: Int,
        input: Int,
        output: Int,
    ): String =
        "{\"type\":\"response.completed\",\"sequence_number\":$seq," +
            "\"response\":{\"id\":\"resp_1\",\"object\":\"response\",\"status\":\"completed\"," +
            "\"usage\":{\"input_tokens\":$input,\"output_tokens\":$output," +
            "\"total_tokens\":${input + output}}}}"

    private fun happyStream(): String =
        sse("response.created", createdJson(0)) +
            sse("response.in_progress", inProgressJson(1)) +
            sse("response.output_item.added", messageItemAddedJson(2)) +
            sse("response.content_part.added", contentPartAddedJson(3)) +
            sse("response.output_text.delta", textDeltaJson(4, "Hel")) +
            sse("response.output_text.delta", textDeltaJson(5, "lo")) +
            sse("response.output_text.done", textDoneJson(6)) +
            sse("response.output_item.done", messageItemDoneJson(7, "completed")) +
            sse("response.completed", completedJson(8, 11, 3))

    // --- tests ---------------------------------------------------------------

    @Test
    fun wrongProtocolIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            OpenAiResponsesProvider(
                config(ProviderProtocol.OPENAI_CHAT_COMPLETIONS),
                { "s" },
                FakeWire(WireResponse(200, emptyMap(), FakeBody(ByteArray(0)))),
            ) { ImagePayload.Base64("aW1hZ2U=") }
        }
    }

    @Test
    fun descriptorCarriesConfigFacts() {
        val wire = FakeWire(WireResponse(200, emptyMap(), FakeBody(ByteArray(0))))
        val p = provider(wire)
        assertEquals("p1", p.descriptor.id)
        assertEquals(ProviderProtocol.OPENAI_RESPONSES, p.descriptor.protocol)
        assertEquals("gpt-test", p.descriptor.model)
    }

    @Test
    fun streamPostsToResponsesEndpointWithBearerAuth() =
        runBlocking {
            val wire =
                FakeWire(
                    WireResponse(
                        200,
                        mapOf("Content-Type" to listOf("text/event-stream")),
                        FakeBody(happyStream().toByteArray()),
                    ),
                )
            val events = provider(wire).stream(userRequest()).toList()
            assertEquals(
                listOf(
                    ModelEvent.TextDelta("Hel"),
                    ModelEvent.TextDelta("lo"),
                    ModelEvent.Usage(11, 3),
                    ModelEvent.Completed("stop"),
                ),
                events,
            )
            val sent = wire.requests.single()
            assertEquals("https://api.openai.com/v1/responses", sent.url)
            assertEquals("POST", sent.method)
            assertEquals("Bearer sekret", sent.headers["Authorization"])
            assertEquals("application/json", sent.headers["Content-Type"])
            val body = String(sent.body!!)
            assertTrue(body.contains("\"model\":\"gpt-test\""))
            assertTrue(body.contains("\"stream\":true"))
        }

    @Test
    fun streamHttpErrorBecomesErrorTerminal() =
        runBlocking {
            val wire = FakeWire(WireResponse(401, emptyMap(), FakeBody("denied".toByteArray())))
            val events = provider(wire).stream(userRequest()).toList()
            assertEquals(listOf<ModelEvent>(ModelEvent.Error(ModelErrorCode.AUTH, false)), events)
        }

    @Test
    fun missingCredentialFailsClosed() {
        val wire = FakeWire(WireResponse(200, emptyMap(), FakeBody(ByteArray(0))))
        val p = provider(wire) { throw IllegalArgumentException("alias not found") }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { p.stream(userRequest()).toList() }
        }
    }

    @Test
    fun modelListParsesIds() =
        runBlocking {
            val wire =
                FakeWire(
                    WireResponse(
                        200,
                        emptyMap(),
                        FakeBody(
                            "{\"object\":\"list\",\"data\":[{\"id\":\"gpt-a\"},{\"id\":\"gpt-b\"}]}".toByteArray(),
                        ),
                    ),
                )
            val p = provider(wire)
            assertEquals(ModelCatalogResult.Listed(listOf("gpt-a", "gpt-b")), p.listModels())
            val sent = wire.requests.single()
            assertEquals("GET", sent.method)
            assertEquals("https://api.openai.com/v1/models", sent.url)
        }

    @Test
    fun modelListErrorIsFailed() =
        runBlocking {
            val wire = FakeWire(WireResponse(404, emptyMap(), FakeBody("nope".toByteArray())))
            val p = provider(wire)
            assertEquals(
                ModelCatalogResult.Failed(
                    ModelErrorCode.PROTOCOL,
                    "HTTP 404 on /models",
                    retryable = false,
                ),
                p.listModels(),
            )
        }

    @Test
    fun validationPassesThroughModelList() =
        runBlocking {
            val wire =
                FakeWire(
                    WireResponse(
                        200,
                        emptyMap(),
                        FakeBody("{\"data\":[{\"id\":\"gpt-a\"}]}".toByteArray()),
                    ),
                )
            val p = provider(wire)
            assertEquals(ProviderCheckResult.Ok, p.validateConfiguration())
        }
}
