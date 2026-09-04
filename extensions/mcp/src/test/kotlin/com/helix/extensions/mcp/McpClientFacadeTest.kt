package com.helix.extensions.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class McpClientFacadeTest {
    @Test
    fun streamableHttpInitializesAndPingsThroughOkHttpEngine() =
        runBlocking {
            McpFixture().use { fixture ->
                val session = McpClients.sdk("helix-test", "1").connect(fixture.endpoint)
                try {
                    assertEquals(
                        McpServerIdentity("fixture", "1.0", "2025-03-26"),
                        session.server,
                    )
                    session.ping()
                    assertEquals(listOf("initialize", "notifications/initialized", "ping"), fixture.methods)
                    assertEquals("2025-03-26", fixture.protocolVersions.last())
                    val initialize = fixture.initializeBodies.single()
                    assertTrue("sampling" !in initialize)
                    assertTrue("elicitation" !in initialize)
                    assertTrue("roots" !in initialize)
                } finally {
                    session.close()
                }
            }
        }

    @Test
    fun facadeCanReconnectAfterAClosedSession() =
        runBlocking {
            McpFixture().use { fixture ->
                val facade = McpClients.sdk("helix-test", "1")
                facade.connect(fixture.endpoint).close()
                facade.connect(fixture.endpoint).close()

                assertEquals(2, fixture.initializeCount.get())
            }
        }

    @Test
    fun streamableHttpReconnectsSseWithLastEventId() =
        runBlocking {
            McpFixture(enableSseReconnect = true).use { fixture ->
                val session = McpClients.sdk("helix-test", "1").connect(fixture.endpoint)
                try {
                    assertTrue("second SSE connection was not observed", fixture.awaitSecondSseConnection())
                    assertEquals(2, fixture.sseGetCount.get())
                    assertEquals(listOf(null, "event-1"), fixture.lastEventIds)
                } finally {
                    session.close()
                }
            }
        }

    @Test
    fun cancelledHandshakeDoesNotBecomeAConnectedSession() =
        runBlocking {
            McpFixture(initializeDelayMillis = 2_000).use { fixture ->
                assertThrows(CancellationException::class.java) {
                    runBlocking {
                        withTimeout(100) {
                            McpClients.sdk("helix-test", "1").connect(fixture.endpoint)
                        }
                    }
                }
                Unit
            }
        }

    @Test
    fun cancelledInFlightPingDoesNotPoisonSession() =
        runBlocking {
            McpFixture(firstPingDelayMillis = 2_000).use { fixture ->
                val session = McpClients.sdk("helix-test", "1").connect(fixture.endpoint)
                try {
                    val cancelled =
                        runCatching {
                            withTimeout(100) { session.ping() }
                        }.exceptionOrNull()
                    assertTrue(cancelled is CancellationException)

                    session.ping()
                    assertEquals(2, fixture.pingCount.get())
                } finally {
                    session.close()
                }
            }
        }

    @Test
    fun largeUnknownInitializeFieldDoesNotCrossFacadeBoundary() =
        runBlocking {
            McpFixture(extraInitializeFieldBytes = 1_048_576).use { fixture ->
                val session = McpClients.sdk("helix-test", "1").connect(fixture.endpoint)
                try {
                    assertEquals("fixture", session.server.name)
                    assertTrue(session.server.toString().length < 256)
                } finally {
                    session.close()
                }
            }
        }

    @Test
    fun toolCallMapsSupportedResultBlocksAndKeepsArgumentsOnTheWireOnly() =
        runBlocking {
            McpFixture().use { fixture ->
                val session = McpClients.sdk("helix-test", "1").connect(fixture.endpoint)
                try {
                    val result =
                        session.callTool(
                            "search",
                            buildJsonObject { put("query", "private-query") },
                        )

                    assertFalse(result.isError)
                    assertEquals(McpResultBlock.Text("answer"), result.blocks.single())
                    assertEquals("1", result.structuredContent?.get("count")?.toString())
                    assertTrue(fixture.toolRequestBodies.single().contains("private-query"))
                    assertTrue(result.toString().contains("private-query").not())
                } finally {
                    session.close()
                }
                Unit
            }
        }

    @Test
    fun toolResultTextOverTheConfiguredBoundIsRejected() =
        runBlocking {
            McpFixture(toolResultText = "x".repeat(32)).use { fixture ->
                val session = McpClients.sdk("helix-test", "1").connect(fixture.endpoint)
                try {
                    assertThrows(IllegalArgumentException::class.java) {
                        runBlocking {
                            session.callTool(
                                "search",
                                buildJsonObject {},
                                McpToolResultLimits(maxTextBytes = 8),
                            )
                        }
                    }
                } finally {
                    session.close()
                }
                Unit
            }
        }

    @Test
    fun publicBoundaryRejectsBlankValues() {
        assertThrows(IllegalArgumentException::class.java) { McpClients.sdk(" ", "1") }
        assertThrows(IllegalArgumentException::class.java) { McpClients.sdk("helix", " ") }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                McpClients.sdk("helix", "1").connect(" ")
            }
        }
    }

    @Test
    fun helixPublicSignaturesDoNotExposeSdkOrKtorTypes() {
        val boundaryTypes =
            listOf(
                McpClientFacade::class.java,
                McpClientSession::class.java,
                McpServerIdentity::class.java,
                McpClients::class.java,
                McpServerConfig::class.java,
                McpCredentialLookup::class.java,
                McpEndpointGate::class.java,
                McpNetworkPermit::class.java,
                McpHostResolver::class.java,
                McpSsrfEndpointGate::class.java,
                McpEndpointDeniedException::class.java,
                McpHandshakeService::class.java,
                McpHandshakeSnapshot::class.java,
                McpMetadataLimits::class.java,
                McpMetadataSnapshot::class.java,
                McpCapabilitySnapshot::class.java,
                McpToolMetadata::class.java,
                McpResourceMetadata::class.java,
                McpPromptMetadata::class.java,
                McpPromptArgumentMetadata::class.java,
                McpToolResultLimits::class.java,
                McpToolResult::class.java,
                McpResultBlock::class.java,
            )
        val publicSignatures =
            boundaryTypes
                .flatMap { type -> type.methods.map { method -> method.toGenericString() } }
                .joinToString("\n")

        assertTrue(publicSignatures, "io.modelcontextprotocol" !in publicSignatures)
        assertTrue(publicSignatures, "io.ktor" !in publicSignatures)
    }
}

private class McpFixture(
    private val initializeDelayMillis: Long = 0,
    private val extraInitializeFieldBytes: Int = 0,
    private val firstPingDelayMillis: Long = 0,
    private val enableSseReconnect: Boolean = false,
    private val toolResultText: String = "answer",
) : AutoCloseable {
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val methods = CopyOnWriteArrayList<String>()
    val initializeCount = AtomicInteger(0)
    val pingCount = AtomicInteger(0)
    val sseGetCount = AtomicInteger(0)
    val lastEventIds = CopyOnWriteArrayList<String?>()
    val protocolVersions = CopyOnWriteArrayList<String?>()
    val toolRequestBodies = CopyOnWriteArrayList<String>()
    val initializeBodies = CopyOnWriteArrayList<String>()
    private val secondSseConnection = CountDownLatch(1)
    val endpoint: String

    init {
        server.executor = executor
        server.createContext("/mcp", ::handle)
        server.start()
        endpoint = "http://127.0.0.1:${server.address.port}/mcp"
    }

    private fun handle(exchange: HttpExchange) {
        exchange.use {
            if (exchange.requestMethod == "GET") {
                handleSseGet(exchange)
                return
            }
            check(exchange.requestMethod == "POST")
            val requestBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val method = METHOD_PATTERN.find(requestBody)?.groupValues?.get(1) ?: error("method missing")
            methods += method
            protocolVersions += exchange.requestHeaders.getFirst("MCP-Protocol-Version")
            val id = ID_PATTERN.find(requestBody)?.groupValues?.get(1)

            when (method) {
                "initialize" -> {
                    initializeBodies += requestBody
                    initializeCount.incrementAndGet()
                    if (initializeDelayMillis > 0) Thread.sleep(initializeDelayMillis)
                    val padding = "x".repeat(extraInitializeFieldBytes)
                    exchange.responseHeaders.add("mcp-session-id", "fixture-session")
                    respondJson(
                        exchange,
                        200,
                        buildString {
                            append("""{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-03-26",""")
                            append(""""capabilities":{"tools":{}},"serverInfo":{"name":"fixture","version":"1.0"},""")
                            append(""""_padding":"$padding"}}""")
                        },
                    )
                }

                "notifications/initialized" -> {
                    exchange.sendResponseHeaders(202, -1)
                }

                "ping" -> {
                    if (pingCount.incrementAndGet() == 1 && firstPingDelayMillis > 0) {
                        Thread.sleep(firstPingDelayMillis)
                    }
                    respondJson(exchange, 200, """{"jsonrpc":"2.0","id":$id,"result":{}}""")
                }

                "tools/call" -> {
                    toolRequestBodies += requestBody
                    respondJson(
                        exchange,
                        200,
                        """{"jsonrpc":"2.0","id":$id,"result":{"content":[""" +
                            """{"type":"text","text":"$toolResultText"}],""" +
                            """"structuredContent":{"count":1},"isError":false}}""",
                    )
                }

                else -> {
                    exchange.sendResponseHeaders(404, -1)
                }
            }
        }
    }

    fun awaitSecondSseConnection(): Boolean = secondSseConnection.await(5, TimeUnit.SECONDS)

    private fun handleSseGet(exchange: HttpExchange) {
        if (!enableSseReconnect) {
            exchange.sendResponseHeaders(405, -1)
            return
        }

        val attempt = sseGetCount.incrementAndGet()
        lastEventIds += exchange.requestHeaders.getFirst("Last-Event-ID")
        if (attempt > 1) {
            secondSseConnection.countDown()
            exchange.sendResponseHeaders(405, -1)
            return
        }

        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.sendResponseHeaders(200, 0)
        exchange.responseBody.use { body ->
            body.write("id: event-1\ndata:\n\n".toByteArray(StandardCharsets.UTF_8))
            body.flush()
        }
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun respondJson(
        exchange: HttpExchange,
        status: Int,
        body: String,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private companion object {
        val METHOD_PATTERN = Regex("\\\"method\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
        val ID_PATTERN = Regex("\\\"id\\\"\\s*:\\s*([^,}]+)")
    }
}
