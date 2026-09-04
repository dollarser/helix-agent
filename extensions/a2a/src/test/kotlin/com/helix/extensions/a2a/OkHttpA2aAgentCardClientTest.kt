package com.helix.extensions.a2a

import com.helix.core.model.NormalizedEndpoint
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class OkHttpA2aAgentCardClientTest {
    private lateinit var server: HttpServer
    private lateinit var executor: java.util.concurrent.ExecutorService
    private lateinit var client: OkHttpA2aAgentCardClient

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        executor = Executors.newCachedThreadPool()
        server.executor = executor
        server.start()
        client = OkHttpA2aAgentCardClient(OkHttpClient.Builder().followRedirects(false).build())
    }

    @After
    fun tearDown() {
        server.stop(0)
        executor.shutdownNow()
    }

    @Test
    fun `public discovery never sends credentials and rejects redirects`() =
        runBlocking {
            val authorization = mutableListOf<String?>()
            server.createContext("/.well-known/agent-card.json") { exchange ->
                authorization += exchange.requestHeaders.getFirst("Authorization")
                exchange.respond(200, "application/json", "{}")
            }
            server.createContext("/redirect") { exchange ->
                exchange.responseHeaders.add("Location", "/.well-known/agent-card.json")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }

            assertEquals("{}", client.fetchPublic(endpoint("/.well-known/agent-card.json")))
            assertEquals(listOf(null), authorization)
            assertFails { client.fetchPublic(endpoint("/redirect")) }
        }

    @Test
    fun `extended JSONRPC and HTTP JSON bind bearer version tenant and media type`() =
        runBlocking {
            val requests = mutableListOf<String>()
            server.createContext("/rpc") { exchange ->
                requests +=
                    "rpc:${exchange.requestHeaders.getFirst(
                        "Authorization",
                    )}:${exchange.requestHeaders.getFirst(
                        "A2A-Version",
                    )}:${exchange.requestBody.bufferedReader().readText()}"
                exchange.respond(
                    200,
                    "application/json",
                    "{\"jsonrpc\":\"2.0\",\"id\":\"helix-agent-card\",\"result\":{\"name\":\"rpc\"}}",
                )
            }
            server.createContext("/rest/extendedAgentCard") { exchange ->
                requests += "rest:${exchange.requestHeaders.getFirst("Authorization")}:${exchange.requestURI.query}"
                exchange.respond(200, "application/a2a+json", "{\"name\":\"rest\"}")
            }

            val rpc = client.fetchExtended(interfaceFor("/rpc", A2aBinding.JSON_RPC), "token")
            val rest = client.fetchExtended(interfaceFor("/rest", A2aBinding.HTTP_JSON), "token")

            assertEquals("{\"name\":\"rpc\"}", rpc)
            assertEquals("{\"name\":\"rest\"}", rest)
            assertTrue(requests[0].contains("rpc:Bearer token:1.0"))
            assertTrue(requests[0].contains("\"method\":\"GetExtendedAgentCard\""))
            assertTrue(requests[0].contains("\"tenant\":\"tenant-1\""))
            assertEquals("rest:Bearer token:tenant=tenant-1", requests[1])
        }

    @Test
    fun `authentication errors non JSON and oversized bodies fail closed without body disclosure`() =
        runBlocking {
            server.createContext(
                "/auth",
            ) { exchange -> exchange.respond(401, "application/json", "{\"secret\":\"do-not-leak\"}") }
            server.createContext("/html") { exchange -> exchange.respond(200, "text/html", "<html></html>") }
            server.createContext("/large") { exchange ->
                exchange.sendResponseHeaders(200, 512L * 1024 + 1)
                exchange.close()
            }

            val auth = failure { client.fetchPublic(endpoint("/auth")) }
            assertFalseContains(auth, "do-not-leak")
            assertFails { client.fetchPublic(endpoint("/html")) }
            assertFails { client.fetchPublic(endpoint("/large")) }
        }

    private fun endpoint(path: String): NormalizedEndpoint =
        NormalizedEndpoint.parse("http://127.0.0.1:${server.address.port}$path")

    private fun interfaceFor(
        path: String,
        binding: A2aBinding,
    ): A2aInterfaceSnapshot = A2aInterfaceSnapshot(endpoint(path), binding, "1.0", "tenant-1")

    private fun HttpExchange.respond(
        status: Int,
        contentType: String,
        body: String,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        responseHeaders.add("Content-Type", contentType)
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private suspend fun failure(block: suspend () -> Unit): String =
        try {
            block()
            error("expected failure")
        } catch (error: IllegalArgumentException) {
            error.message.orEmpty()
        }

    private suspend fun assertFails(block: suspend () -> Unit) {
        failure(block)
    }

    private fun assertFalseContains(
        actual: String,
        forbidden: String,
    ) {
        assertTrue("unexpected sensitive body in error", forbidden !in actual)
    }
}
