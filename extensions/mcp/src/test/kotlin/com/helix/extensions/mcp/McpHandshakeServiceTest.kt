package com.helix.extensions.mcp

import com.helix.core.model.ProviderResidence
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class McpHandshakeServiceTest {
    @Test
    fun configIsDisabledByDefaultAndSnapshotsBoundedMetadata() =
        runBlocking {
            MetadataFixture().use { fixture ->
                val config =
                    McpServerConfig.disabled(
                        id = "mcp-fixture",
                        endpointUrl = fixture.endpoint,
                        bearerSecretAlias = "mcp.fixture.token",
                    )
                assertFalse(config.enabled)

                val snapshot =
                    McpHandshakeService(
                        credentials = McpCredentialLookup { "fixture-secret" },
                        endpointGate = ALLOW_ENDPOINT,
                        clientName = "helix-test",
                        clientVersion = "1",
                    ).testConnection(
                        config,
                        McpMetadataLimits(
                            maxItemsPerKind = 1,
                            maxTextChars = 16,
                            maxSchemaBytes = 1_024,
                            maxPromptArguments = 1,
                        ),
                    )

                assertHandshakeSnapshot(snapshot, fixture)
            }
        }

    @Test
    fun missingCredentialFailsBeforeAnyNetworkRequest() {
        MetadataFixture().use { fixture ->
            val service =
                McpHandshakeService(
                    credentials = McpCredentialLookup { throw IllegalArgumentException("secret unavailable") },
                    endpointGate = ALLOW_ENDPOINT,
                    clientName = "helix-test",
                    clientVersion = "1",
                )
            val config = McpServerConfig.disabled("mcp-fixture", fixture.endpoint, "missing.alias")

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { service.testConnection(config) }
            }
            assertEquals(0, fixture.requestCount.get())

            listOf("", "bad token", "bad\r\ntoken").forEach { invalid ->
                val invalidService =
                    McpHandshakeService(
                        credentials = McpCredentialLookup { invalid },
                        endpointGate = ALLOW_ENDPOINT,
                        clientName = "helix-test",
                        clientVersion = "1",
                    )
                assertThrows(IllegalArgumentException::class.java) {
                    runBlocking { invalidService.testConnection(config) }
                }
            }
            assertEquals(0, fixture.requestCount.get())
        }
    }

    @Test
    fun publicCleartextIsRejectedWhileLanConfigStillNeedsTheRuntimeGate() {
        assertThrows(IllegalArgumentException::class.java) {
            McpServerConfig.disabled("public-http", "http://example.com/mcp")
        }
        val lan =
            McpServerConfig.disabled(
                id = "lan-http",
                endpointUrl = "http://192.168.1.20/mcp",
            )
        assertEquals(ProviderResidence.USER_AUTHORIZED_LAN, lan.endpoint.residence())
        assertFalse(lan.enabled)
    }

    @Test
    fun endpointGateRunsBeforeCredentialLookupAndNetwork() {
        MetadataFixture().use { fixture ->
            val credentialReads = AtomicInteger(0)
            val service =
                McpHandshakeService(
                    credentials =
                        McpCredentialLookup {
                            credentialReads.incrementAndGet()
                            "must-not-be-read"
                        },
                    endpointGate = McpEndpointGate { throw IllegalArgumentException("scope denied") },
                    clientName = "helix-test",
                    clientVersion = "1",
                )
            val config = McpServerConfig.disabled("mcp-gated", fixture.endpoint, "mcp.gated.token")

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { service.testConnection(config) }
            }
            assertEquals(0, credentialReads.get())
            assertEquals(0, fixture.requestCount.get())
        }
    }

    @Test
    fun canonicalSchemaHashIgnoresObjectKeyOrder() =
        runBlocking {
            MetadataFixture().use { fixture ->
                val snapshot =
                    McpHandshakeService(
                        credentials = McpCredentialLookup { error("no credential expected") },
                        endpointGate = ALLOW_ENDPOINT,
                        clientName = "helix-test",
                        clientVersion = "1",
                    ).testConnection(
                        McpServerConfig.disabled("mcp-schema", fixture.endpoint),
                        McpMetadataLimits(maxItemsPerKind = 2),
                    )

                assertEquals(2, snapshot.metadata.tools.size)
                assertEquals(
                    snapshot.metadata.tools[0].schemaHash,
                    snapshot.metadata.tools[1].schemaHash,
                )
            }
        }

    @Test
    fun totalMetadataBudgetFailsClosed() {
        MetadataFixture().use { fixture ->
            val service =
                McpHandshakeService(
                    credentials = McpCredentialLookup { error("no credential expected") },
                    endpointGate = ALLOW_ENDPOINT,
                    clientName = "helix-test",
                    clientVersion = "1",
                )

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    service.testConnection(
                        McpServerConfig.disabled("mcp-budget", fixture.endpoint),
                        McpMetadataLimits(maxTotalMetadataBytes = 16),
                    )
                }
            }
            assertFalse(fixture.methods.any { it == "resources/read" || it == "prompts/get" })
        }
    }

    @Test
    fun redirectIsRejectedBeforeBearerCanReachAnotherOrigin() =
        runBlocking {
            RedirectFixture().use { fixture ->
                val config = McpServerConfig.disabled("mcp-redirect", fixture.endpoint, "mcp.redirect.token")
                val service =
                    McpHandshakeService(
                        credentials = McpCredentialLookup { "redirect-secret" },
                        endpointGate = ALLOW_ENDPOINT,
                        clientName = "helix-test",
                        clientVersion = "1",
                    )

                assertThrows(Exception::class.java) {
                    runBlocking { service.testConnection(config) }
                }

                assertTrue(fixture.originAuthorizations.all { it == "Bearer redirect-secret" })
                assertTrue(fixture.redirectAuthorizations.isEmpty())
            }
        }
}

private fun assertHandshakeSnapshot(
    snapshot: McpHandshakeSnapshot,
    fixture: MetadataFixture,
) {
    assertEquals(fixture.endpoint, snapshot.endpoint)
    assertEquals(ProviderResidence.ON_DEVICE_LOOPBACK, snapshot.residence)
    assertEquals("2025-03-26", snapshot.identity.negotiatedProtocolVersion)
    assertEquals(
        McpCapabilitySnapshot(true, true, true, true, true, true, true),
        snapshot.metadata.capabilities,
    )
    assertEquals(1, snapshot.metadata.tools.size)
    assertEquals(
        64,
        snapshot.metadata.tools
            .single()
            .schemaHash.length,
    )
    assertEquals(
        "description-long".take(16),
        snapshot.metadata.tools
            .single()
            .description,
    )
    assertTrue(snapshot.metadata.toolsHaveMore)
    assertEquals(1, snapshot.metadata.resources.size)
    assertTrue(snapshot.metadata.resourcesHaveMore)
    assertEquals(
        1,
        snapshot.metadata.prompts
            .single()
            .arguments.size,
    )
    assertTrue(
        snapshot.metadata.prompts
            .single()
            .argumentsTruncated,
    )
    assertTrue(snapshot.metadata.promptsHaveMore)
    assertEquals(
        listOf("initialize", "notifications/initialized", "tools/list", "resources/list", "prompts/list"),
        fixture.methods,
    )
    assertTrue(fixture.authorizations.all { it == "Bearer fixture-secret" })
    assertFalse(snapshot.toString().contains("fixture-secret"))
}

private class MetadataFixture : AutoCloseable {
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val requestCount = AtomicInteger(0)
    val methods = CopyOnWriteArrayList<String>()
    val authorizations = CopyOnWriteArrayList<String?>()
    val endpoint: String

    init {
        server.executor = executor
        server.createContext("/mcp", ::handle)
        server.start()
        endpoint = "http://127.0.0.1:${server.address.port}/mcp"
    }

    private fun handle(exchange: HttpExchange) {
        exchange.use {
            requestCount.incrementAndGet()
            authorizations += exchange.requestHeaders.getFirst("Authorization")
            val requestBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val method = METHOD_PATTERN.find(requestBody)?.groupValues?.get(1) ?: error("method missing")
            val id = ID_PATTERN.find(requestBody)?.groupValues?.get(1)
            methods += method
            when (method) {
                "initialize" -> {
                    respondInitialize(exchange, id)
                }

                "notifications/initialized" -> {
                    exchange.sendResponseHeaders(202, -1)
                }

                "tools/list" -> {
                    respondTools(exchange, id)
                }

                "resources/list" -> {
                    respondResources(exchange, id)
                }

                "prompts/list" -> {
                    respondPrompts(exchange, id)
                }

                else -> {
                    exchange.sendResponseHeaders(404, -1)
                }
            }
        }
    }

    private fun respondInitialize(
        exchange: HttpExchange,
        id: String?,
    ) {
        exchange.responseHeaders.add("mcp-session-id", "metadata-session")
        exchange.respondJson(
            200,
            """
            {"jsonrpc":"2.0","id":$id,"result":{
              "protocolVersion":"2025-03-26",
              "capabilities":{"tools":{"listChanged":true},
                "resources":{"listChanged":true,"subscribe":true},
                "prompts":{"listChanged":true}},
              "serverInfo":{"name":"metadata-fixture","version":"1"}}}
            """.trimIndent(),
        )
    }

    private fun respondTools(
        exchange: HttpExchange,
        id: String?,
    ) = exchange.respondJson(
        200,
        """
        {"jsonrpc":"2.0","id":$id,"result":{"tools":[
          {"name":"first-tool","description":"description-long",
           "inputSchema":{"type":"object","properties":{
             "alpha":{"type":"string"},"beta":{"type":"integer"}},"required":["alpha"]}},
          {"name":"second-tool","inputSchema":{"required":["alpha"],
           "properties":{"beta":{"type":"integer"},"alpha":{"type":"string"}},
           "type":"object"}}],"nextCursor":"more-tools"}}
        """.trimIndent(),
    )

    private fun respondResources(
        exchange: HttpExchange,
        id: String?,
    ) = exchange.respondJson(
        200,
        """
        {"jsonrpc":"2.0","id":$id,"result":{"resources":[
          {"uri":"fixture://one","name":"resource-one","description":"resource description",
           "mimeType":"text/plain","size":12},{"uri":"fixture://two","name":"resource-two"}]}}
        """.trimIndent(),
    )

    private fun respondPrompts(
        exchange: HttpExchange,
        id: String?,
    ) = exchange.respondJson(
        200,
        """
        {"jsonrpc":"2.0","id":$id,"result":{"prompts":[
          {"name":"prompt-one","description":"prompt description","arguments":[
            {"name":"first","required":true},{"name":"second","required":false}]}],
          "nextCursor":"more-prompts"}}
        """.trimIndent(),
    )

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}

private class RedirectFixture : AutoCloseable {
    private val executor = Executors.newCachedThreadPool()
    private val origin = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val redirect = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val originAuthorizations = CopyOnWriteArrayList<String?>()
    val redirectAuthorizations = CopyOnWriteArrayList<String?>()
    val endpoint: String

    init {
        origin.executor = executor
        redirect.executor = executor
        redirect.createContext("/mcp", ::handleRedirectTarget)
        redirect.start()
        origin.createContext("/mcp") { exchange ->
            exchange.use {
                originAuthorizations += exchange.requestHeaders.getFirst("Authorization")
                exchange.responseHeaders.add("Location", "http://127.0.0.1:${redirect.address.port}/mcp")
                exchange.sendResponseHeaders(307, -1)
            }
        }
        origin.start()
        endpoint = "http://127.0.0.1:${origin.address.port}/mcp"
    }

    private fun handleRedirectTarget(exchange: HttpExchange) {
        exchange.use {
            redirectAuthorizations += exchange.requestHeaders.getFirst("Authorization")
            val requestBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val method = METHOD_PATTERN.find(requestBody)?.groupValues?.get(1) ?: error("method missing")
            val id = ID_PATTERN.find(requestBody)?.groupValues?.get(1)
            when (method) {
                "initialize" -> {
                    exchange.responseHeaders.add("mcp-session-id", "redirect-session")
                    exchange.respondJson(
                        200,
                        """
                        {"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-03-26",
                          "capabilities":{},"serverInfo":{"name":"redirect-fixture","version":"1"}}}
                        """.trimIndent(),
                    )
                }

                "notifications/initialized" -> {
                    exchange.sendResponseHeaders(202, -1)
                }

                else -> {
                    exchange.sendResponseHeaders(404, -1)
                }
            }
        }
    }

    override fun close() {
        origin.stop(0)
        redirect.stop(0)
        executor.shutdownNow()
    }
}

private fun HttpExchange.respondJson(
    status: Int,
    body: String,
) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    responseHeaders.add("Content-Type", "application/json")
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

private val METHOD_PATTERN = Regex("\\\"method\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
private val ID_PATTERN = Regex("\\\"id\\\"\\s*:\\s*([^,}]+)")
private val ALLOW_ENDPOINT =
    McpEndpointGate { endpoint ->
        McpNetworkPermit(endpoint.host, listOf(byteArrayOf(127, 0, 0, 1)))
    }
