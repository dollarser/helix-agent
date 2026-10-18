package com.helix.app.provider

import com.helix.core.model.ArtifactRef
import com.helix.core.model.ImageReference
import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.ModelToolSchema
import com.helix.core.model.ToolName
import com.helix.provider.api.ModelCatalogResult
import com.helix.provider.api.ProviderCheckResult
import com.helix.provider.api.wire.OkHttpWireClient
import com.helix.provider.api.wire.PublicClientWireClient
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real local HTTP sockets behind the production pinned wire; no external service or credentials. */
class BuiltInModelTransportTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val requests = CopyOnWriteArrayList<Pair<Map<String, List<String>>, String>>()
    private val release = CountDownLatch(1)
    private var reply: (HttpExchange) -> Unit = { respond(it, 200, TEXT_STREAM) }
    private val http = OkHttpWireClient(followRedirects = false, retryConnections = false, readTimeoutMillis = 2_000)
    private val local =
        object : WireClient {
            override suspend fun open(request: WireRequest): WireResponse {
                assertEquals(BuiltInModel.GENERATION_URL, request.url)
                // Test-only route substitution after production origin/header checks, never shipped to the app.
                val url = "http://127.0.0.1:${server.address.port}/v1/chat/completions"
                return http.open(WireRequest(request.method, url, request.headers, request.body))
            }
        }
    private val forbidden =
        object : WireClient {
            override suspend fun open(request: WireRequest): WireResponse = error("No fallback to custom provider")
        }
    private val factory =
        ProviderFactory(
            { error("No secret lookup") },
            forbidden,
            {
                object : VisionImageSource {
                    override fun load(ref: ArtifactRef): LoadedImage = error("Image binding must be checked")

                    override fun load(
                        image: ImageReference,
                        config: com.helix.provider.api.ProviderConfig,
                    ): LoadedImage {
                        assertEquals(BuiltInModel.PROVIDER_ID, config.id)
                        assertEquals("fixture-image", image.ref.value)
                        return ArtifactVisionImageSource.PROBE_IMAGE
                    }
                }
            },
            builtInWire = PublicClientWireClient(BuiltInModel.GENERATION_URL, BuiltInModel.CLIENT_ID, local),
            additionalFactory = { error("No alternate adapter") },
        )
    private val provider = factory.create(builtInConfig())
    private val request = ModelRequest(BuiltInModel.MODEL_ID, listOf(ModelMessage(ModelRole.USER, "hello")))

    init {
        server.createContext("/") { exchange ->
            requests += exchange.requestHeaders.toMap() to exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            reply(exchange)
        }
        server.start()
    }

    @After fun stop() {
        release.countDown()
        server.stop(0)
    }

    @Test fun textImagesAndFragmentedToolCallsUseExistingAdapterAndPublicHeader() =
        runBlocking {
            reply = { respond(it, 200, TOOL_STREAM) }
            val image = ImageReference(ArtifactRef("fixture-image"), "image/png")
            val schema = "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}"
            val events =
                provider
                    .stream(
                        request.copy(
                            messages = listOf(ModelMessage(ModelRole.USER, "inspect", listOf(image))),
                            tools = listOf(ModelToolSchema(ToolName("read"), "Read fixture", schema)),
                        ),
                    ).toList()
            assertTrue(events.any { it is ModelEvent.ToolCallStarted })
            assertTrue(events.any { it is ModelEvent.ToolCallFinished })
            val fragments = events.filterIsInstance<ModelEvent.ToolArgumentsDelta>()
            val arguments = fragments.joinToString("") { it.jsonFragment }
            assertEquals("{\"path\":\"fixture\"}", arguments)
            assertTrue(events.any { it is ModelEvent.Completed })
            val (headers, body) = requests.single()
            val identity = headers.entries.single { it.key.equals("X-Client-Id", true) }.value
            assertEquals(listOf(BuiltInModel.CLIENT_ID), identity)
            assertFalse(headers.keys.any { it.equals("Authorization", true) || it.equals("Cookie", true) })
            assertTrue(body.contains("\"model\":\"${BuiltInModel.MODEL_ID}\""))
            assertTrue(body.contains("\"stream\":true"))
            assertTrue(body.contains("data:image/png;base64,${ArtifactVisionImageSource.PROBE_IMAGE.base64}"))
            assertTrue(body.contains("\"tools\""))
        }

    @Test fun errorsAreClassifiedWithoutRetryFallbackOrServerBodyExposure() =
        runBlocking {
            val cases =
                listOf(
                    401 to ModelErrorCode.AUTH,
                    429 to ModelErrorCode.RATE_LIMITED,
                    502 to ModelErrorCode.SERVER_ERROR,
                    503 to ModelErrorCode.SERVER_ERROR,
                    504 to ModelErrorCode.TIMEOUT,
                )
            for ((status, expected) in cases) {
                reply = {
                    it.responseHeaders.add("Retry-After", "0")
                    respond(it, status, "private server diagnostic")
                }
                val before = requests.size
                val event = provider.stream(request).toList().single() as ModelEvent.Error
                assertEquals(expected, event.code)
                assertFalse(event.retryable)
                assertEquals(before + 1, requests.size)
            }
        }

    @Test fun redirectsCannotForwardIdentityOrReplayPost() =
        runBlocking {
            val target = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            var hits = 0
            target.createContext("/") {
                hits++
                respond(it, 200, TEXT_STREAM)
            }
            target.start()
            try {
                for (status in listOf(301, 302, 307, 308)) {
                    reply = {
                        it.responseHeaders.add("Location", "http://127.0.0.1:${target.address.port}/leak")
                        respond(it, status, "redirect")
                    }
                    assertTrue(provider.stream(request).toList().single() is ModelEvent.Error)
                }
                assertEquals(4, requests.size)
                assertEquals(0, hits)
            } finally {
                target.stop(0)
            }
        }

    @Test fun metadataDoesNotGenerateAndExplicitConnectionTestReallyStreams() =
        runBlocking {
            assertEquals(ModelCatalogResult.Unsupported, provider.listModels())
            assertEquals(null, provider.contextWindow(BuiltInModel.MODEL_ID))
            assertTrue(requests.isEmpty())
            assertEquals(ProviderCheckResult.Ok, provider.validateConfiguration())
            assertEquals(1, requests.size)
            val events = provider.stream(request).toList()
            val answer = events.filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text }
            assertEquals("OK", answer)
        }

    @Test fun wrongModelFailsBeforeNetwork() {
        assertThrows(IllegalArgumentException::class.java) { provider.stream(request.copy(model = "paid-other")) }
        assertThrows(IllegalArgumentException::class.java) { factory.create(builtInConfig().copy(model = "other")) }
        assertTrue(requests.isEmpty())
    }

    @Test fun toolResultBackfillRetainsCallIdentityAndUsesSameModel() =
        runBlocking {
            reply = { respond(it, 200, TOOL_STREAM) }
            val events = provider.stream(request).toList()
            val started = events.filterIsInstance<ModelEvent.ToolCallStarted>().single()
            val fragments = events.filterIsInstance<ModelEvent.ToolArgumentsDelta>()
            val name = ToolName(started.name)
            val call =
                com.helix.core.model.AssistantToolCall(
                    started.id,
                    name,
                    fragments.joinToString("") { it.jsonFragment },
                )
            val assistant = ModelMessage(ModelRole.ASSISTANT, "", toolCalls = listOf(call))
            val result = ModelMessage(ModelRole.TOOL, "fixture-result", toolCallId = started.id, toolName = name)
            reply = { respond(it, 200, TEXT_STREAM) }
            val final = provider.stream(request.copy(messages = request.messages + assistant + result)).toList()
            assertTrue(final.any { it is ModelEvent.Completed })
            val body = requests.last().second
            assertTrue(body.contains("\"tool_call_id\":\"${started.id.value}\""))
            assertTrue(body.contains("fixture-result"))
            assertEquals(2, requests.size)
        }

    @Test fun incompleteToolStreamIsNotCompletedOrReplayed() =
        runBlocking {
            reply = { respond(it, 200, TOOL_STREAM.substringBefore("\"finish_reason\"")) }
            val events = provider.stream(request).toList()
            assertTrue(events.any { it is ModelEvent.ToolCallStarted })
            assertFalse(events.any { it is ModelEvent.Completed })
            assertFalse(events.any { it is ModelEvent.ToolCallFinished })
            assertFalse(events.filterIsInstance<ModelEvent.Error>().single().retryable)
            assertEquals(1, requests.size)
        }

    @Test fun cancellationAfterPartialStreamExitsWithoutCompletionOrRetry() =
        runBlocking {
            reply = {
                it.responseHeaders.add("Content-Type", "text/event-stream")
                it.sendResponseHeaders(200, 0)
                val chunk = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"}}]}\n\n"
                it.responseBody.write(chunk.toByteArray())
                it.responseBody.flush()
                release.await(10, TimeUnit.SECONDS)
                it.close()
            }
            val partial = CompletableDeferred<Unit>()
            val events = mutableListOf<ModelEvent>()
            val job =
                launch {
                    provider.stream(request).collect {
                        events += it
                        if (it is ModelEvent.TextDelta) partial.complete(Unit)
                    }
                }
            withTimeout(5_000) {
                partial.await()
                job.cancelAndJoin()
            }
            assertTrue(job.isCancelled)
            assertFalse(events.any { it is ModelEvent.Completed })
            assertEquals(1, requests.size)
        }

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        body: String,
    ) {
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.sendResponseHeaders(status, 0)
        exchange.responseBody.use { output ->
            body.chunked(17).forEach {
                output.write(it.toByteArray())
                output.flush()
            }
        }
    }

    private companion object {
        const val TEXT_STREAM =
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"OK\"}}]}\n\n" +
                "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
        const val TOOL_STREAM =
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{" +
                "\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{" +
                "\"name\":\"read\",\"arguments\":\"{\\\"path\\\":\"}}]}}]}\n\n" +
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0," +
                "\"function\":{\"arguments\":\"\\\"fixture\\\"}\"}}]}}]}\n\n" +
                "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n"
    }
}
