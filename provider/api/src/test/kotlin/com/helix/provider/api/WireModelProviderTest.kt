package com.helix.provider.api

import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SecretAlias
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
import java.io.IOException
import java.net.SocketTimeoutException

class WireModelProviderTest {
    // --- fakes -------------------------------------------------------------

    private class FakeBody(
        bytes: ByteArray,
        chunkSize: Int = 64,
        val closed: BooleanArray = BooleanArray(1),
    ) : WireBody {
        private val data = bytes
        private val chunk = chunkSize

        override suspend fun bytes(): ByteArray = data.copyOf()

        override suspend fun forEachChunk(onChunk: suspend (ByteArray) -> Boolean) {
            var i = 0
            while (i < data.size) {
                val end = minOf(i + chunk, data.size)
                if (!onChunk(data.copyOfRange(i, end))) break
                i = end
            }
        }

        override fun close() {
            closed[0] = true
        }
    }

    private class FakeWire(
        private val handler: suspend (WireRequest) -> WireResponse,
    ) : WireClient {
        val requests = ArrayList<WireRequest>()
        var ioFailure: IOException? = null

        override suspend fun open(request: WireRequest): WireResponse {
            requests += request
            ioFailure?.let { throw it }
            return handler(request)
        }
    }

    /** Emits the script exactly once: on the first non-empty chunk, or at finish. */
    private class ScriptedDecoder(
        private val perStream: List<ModelEvent> =
            listOf(
                ModelEvent.TextDelta("ok"),
                ModelEvent.Completed("stop"),
            ),
    ) : StreamDecoder {
        private var emitted = false

        override fun feed(chunk: ByteArray): List<ModelEvent> =
            if (chunk.isEmpty() || emitted) {
                emptyList()
            } else {
                emitted = true
                perStream
            }

        override fun finish(): List<ModelEvent> {
            if (emitted) return emptyList()
            emitted = true
            return perStream
        }
    }

    private class TestProvider(
        config: ProviderConfig,
        credentials: CredentialLookup,
        wire: WireClient,
        private val streamResource: String = "chat/completions",
        private val modelsResource: String? = "models",
        private val newTestDecoder: () -> StreamDecoder = { ScriptedDecoder() },
        private val authOf: (String) -> Map<String, String> =
            { value -> mapOf("Authorization" to "Bearer $value") },
    ) : WireModelProvider(
            descriptor =
                ProviderDescriptor(
                    id = config.id,
                    displayName = config.displayName,
                    protocol = config.protocol,
                    model = config.model,
                    endpoint = config.endpoint,
                ),
            credentials = credentials,
            wire = wire,
            encoder = { "{}" },
            newDecoder = newTestDecoder,
            secretAlias = config.secretAlias,
            extraHeaders = config.headers,
        ) {
        override fun streamPath(): String = streamResource

        override fun modelsPath(): String? = modelsResource

        override fun authHeaders(): Map<String, String> = authOf(resolveCredential(credentials, secretAlias))
    }

    private fun config(
        endpoint: String = "https://example.test:443/v1",
        headers: Map<String, String> = emptyMap(),
    ): ProviderConfig =
        ProviderConfig(
            id = "p1",
            displayName = "Test",
            protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
            endpoint = NormalizedEndpoint.parse(endpoint),
            model = "test-model",
            headers = headers,
            secretAlias = SecretAlias("alias_1"),
            capabilitySnapshot = ProviderCapabilities.toJsonString(DEFAULTS),
        )

    private fun okJson(body: String) = WireResponse(200, emptyMap(), FakeBody(body.toByteArray()))

    private fun userMsg() = ModelMessage(ModelRole.USER, "hi")

    private fun request() = ModelRequest("m", listOf(userMsg()))

    private companion object {
        val DEFAULTS =
            ProviderCapabilities(
                streaming = true,
                toolCalls = true,
                parallelToolCalls = false,
                vision = false,
                reasoning = false,
                jsonSchemaOutput = false,
                maxContextTokens = null,
                source = CapabilitySource.PROBED,
            )
    }

    // --- stream ------------------------------------------------------------

    @Test
    fun streamEmitsDecoderEventsAndClosesBody() =
        runBlocking {
            val closed = BooleanArray(1)
            val wire =
                FakeWire {
                    WireResponse(
                        200,
                        emptyMap(),
                        FakeBody("payload".toByteArray(), closed = closed),
                    )
                }
            val provider = TestProvider(config(), { "x" }, wire)
            val events = provider.stream(request()).toList()
            assertEquals(
                listOf<ModelEvent>(ModelEvent.TextDelta("ok"), ModelEvent.Completed("stop")),
                events,
            )
            assertTrue(closed[0])
            val sent = wire.requests.single()
            assertEquals("https://example.test/v1/chat/completions", sent.url)
            assertEquals("POST", sent.method)
            assertEquals("application/json", sent.headers["Content-Type"])
            assertEquals("Bearer x", sent.headers["Authorization"])
            assertEquals("{}", String(sent.body!!))
        }

    @Test
    fun non2xxMapsToErrorTerminal() =
        runBlocking {
            val wire = FakeWire { WireResponse(401, emptyMap(), FakeBody("denied".toByteArray())) }
            val provider = TestProvider(config(), { "x" }, wire)
            val events = provider.stream(request()).toList()
            assertEquals(listOf<ModelEvent>(ModelEvent.Error(ModelErrorCode.AUTH, false)), events)
        }

    @Test
    fun throttledMapsToRetryableError() =
        runBlocking {
            val wire = FakeWire { WireResponse(429, emptyMap(), FakeBody(ByteArray(0))) }
            val provider = TestProvider(config(), { "x" }, wire)
            val events = provider.stream(request()).toList()
            assertEquals(listOf<ModelEvent>(ModelEvent.Error(ModelErrorCode.RATE_LIMITED, true)), events)
        }

    @Test
    fun ioFailureMapsToTransport() =
        runBlocking {
            val wire = FakeWire { error("unused") }
            wire.ioFailure = IOException("dns")
            val provider = TestProvider(config(), { "x" }, wire)
            val events = provider.stream(request()).toList()
            assertEquals(listOf<ModelEvent>(ModelEvent.Error(ModelErrorCode.TRANSPORT, true)), events)
        }

    @Test
    fun timeoutFailureMapsToTimeout() =
        runBlocking {
            val wire = FakeWire { error("unused") }
            wire.ioFailure = SocketTimeoutException("stalled")
            val provider = TestProvider(config(), { "x" }, wire)
            val events = provider.stream(request()).toList()
            assertEquals(listOf<ModelEvent>(ModelEvent.Error(ModelErrorCode.TIMEOUT, true)), events)
        }

    @Test
    fun headerCollisionIsAConfigurationError() {
        runBlocking {
            val wire = FakeWire { okJson("") }
            // `anthropic-version` is allowed by the user-header allowlist (no
            // credential-looking part) but is owned by the protocol auth layer:
            // a case-insensitive collision is a configuration error.
            val provider =
                TestProvider(
                    config(headers = mapOf("anthropic-version" to "2099-01-01")),
                    { "x" },
                    wire,
                    authOf =
                        { value ->
                            mapOf(
                                "Authorization" to "Bearer $value",
                                "anthropic-version" to "2023-06-01",
                            )
                        },
                )
            assertThrows(
                IllegalArgumentException::class.java,
            ) {
                runBlocking { provider.stream(request()).toList() }
            }
        }
    }

    @Test
    fun missingCredentialFailsClosed() {
        runBlocking {
            val wire = FakeWire { okJson("") }
            val provider =
                TestProvider(
                    config(),
                    { throw IOException("alias not found") },
                    wire,
                )
            assertThrows(IOException::class.java) {
                runBlocking { provider.stream(request()).toList() }
            }
        }
    }

    // --- listModels ----------------------------------------------------------

    @Test
    fun modelListParsesOpenAiShape() =
        runBlocking {
            val wire = FakeWire { okJson("{\"data\":[{\"id\":\"a\"},{\"id\":\"b\"}]}") }
            val provider = TestProvider(config(), { "x" }, wire)
            val result = provider.listModels()
            assertEquals(ModelCatalogResult.Listed(listOf("a", "b")), result)
            val sent = wire.requests.single()
            assertEquals("GET", sent.method)
            assertEquals("https://example.test/v1/models", sent.url)
            assertEquals("Bearer x", sent.headers["Authorization"])
        }

    @Test
    fun modelListClosesBodyOnFailure() =
        runBlocking {
            val closed = BooleanArray(1)
            val wire =
                FakeWire {
                    WireResponse(
                        404,
                        emptyMap(),
                        FakeBody("missing".toByteArray(), closed = closed),
                    )
                }
            val provider = TestProvider(config(), { "x" }, wire)
            val result = provider.listModels()
            assertTrue(closed[0])
            assertEquals(
                ModelCatalogResult.Failed(ModelErrorCode.PROTOCOL, "HTTP 404 on /models", false),
                result,
            )
        }

    @Test
    fun modelListMalformedJsonFailsClosed() =
        runBlocking {
            val wire = FakeWire { okJson("not json") }
            val provider = TestProvider(config(), { "x" }, wire)
            val result = provider.listModels()
            assertTrue(result is ModelCatalogResult.Failed)
            assertEquals(ModelErrorCode.PROTOCOL, (result as ModelCatalogResult.Failed).code)
        }

    @Test
    fun modelListIoFailureIsRetryableTransport() =
        runBlocking {
            val wire = FakeWire { error("unused") }
            wire.ioFailure = IOException("refused")
            val provider = TestProvider(config(), { "x" }, wire)
            val result = provider.listModels()
            assertEquals(
                ModelCatalogResult.Failed(ModelErrorCode.TRANSPORT, "IOException", true),
                result,
            )
        }

    @Test
    fun modelListWithoutEndpointIsUnsupported() =
        runBlocking {
            val wire = FakeWire { okJson("") }
            val provider = TestProvider(config(), { "x" }, wire, modelsResource = null)
            assertEquals(ModelCatalogResult.Unsupported, provider.listModels())
        }

    // --- validateConfiguration ----------------------------------------------

    @Test
    fun validationDelegatesToModelListWhenAvailable() =
        runBlocking {
            val wire = FakeWire { okJson("{\"data\":[{\"id\":\"a\"}]}") }
            val provider = TestProvider(config(), { "x" }, wire)
            assertEquals(ProviderCheckResult.Ok, provider.validateConfiguration())
        }

    @Test
    fun validationWithoutModelsUsesMinimalStream() =
        runBlocking {
            val wire = FakeWire { okJson("") }
            val provider = TestProvider(config(), { "x" }, wire, modelsResource = null)
            assertEquals(ProviderCheckResult.Ok, provider.validateConfiguration())
            val sent = wire.requests.single()
            assertEquals("https://example.test/v1/chat/completions", sent.url)
        }

    @Test
    fun validationWithoutModelsPropagatesStreamError() =
        runBlocking {
            val wire = FakeWire { WireResponse(401, emptyMap(), FakeBody(ByteArray(0))) }
            val provider = TestProvider(config(), { "x" }, wire, modelsResource = null)
            val result = provider.validateConfiguration()
            assertTrue(result is ProviderCheckResult.Failed)
            assertEquals(ModelErrorCode.AUTH, (result as ProviderCheckResult.Failed).code)
        }

    // --- url joining ---------------------------------------------------------

    @Test
    fun urlJoiningHandlesPathShapes() =
        runBlocking {
            val wire = FakeWire { okJson("") }
            val noPath = TestProvider(config(endpoint = "https://example.test:443"), { "x" }, wire)
            noPath.stream(request()).toList()
            assertEquals("https://example.test/chat/completions", wire.requests.single().url)
            val trailing =
                TestProvider(config(endpoint = "https://example.test:443/v1/"), { "x" }, wire)
            trailing.stream(request()).toList()
            assertEquals("https://example.test/v1/chat/completions", wire.requests.last().url)
        }
}
