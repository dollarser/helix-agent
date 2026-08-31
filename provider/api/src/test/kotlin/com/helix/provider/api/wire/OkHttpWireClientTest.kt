package com.helix.provider.api.wire

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress

/**
 * Real-socket tests of the OkHttp transport against a localhost
 * [HttpServer] (JDK facility — no test-network dependency). No external
 * network is touched.
 */
class OkHttpWireClientTest {
    private lateinit var server: HttpServer
    private lateinit var base: String
    private var handler: suspend (path: String, body: ByteArray) -> Pair<Int, String> = { path, _ ->
        200 to "hello $path"
    }

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(
            "/",
        ) { exchange ->
            val body = exchange.requestBody.readBytes()
            val (status, payload) =
                runBlocking { handler(exchange.requestURI.path, body) }
            val bytes = payload.toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/plain")
            exchange.responseHeaders.add("X-Test", "1")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun getReturnsStatusHeadersAndBody() =
        runBlocking {
            val client = OkHttpWireClient()
            val response = client.open(WireRequest("GET", "$base/ping", emptyMap(), null))
            assertEquals(200, response.status)
            val testName =
                response.headers.keys.firstOrNull { it.equals("x-test", ignoreCase = true) }
            assertEquals("x-test", testName?.lowercase())
            assertEquals(listOf("1"), response.headers[testName])
            assertArrayEquals("hello /ping".toByteArray(), response.body.bytes())
            response.body.close()
        }

    @Test
    fun postForwardsBodyAndEchoes() =
        runBlocking {
            handler = { _, body -> 200 to String(body) }
            val client = OkHttpWireClient()
            val response =
                client.open(
                    WireRequest(
                        "POST",
                        "$base/echo",
                        mapOf("X-Req" to "y"),
                        "payload".toByteArray(),
                    ),
                )
            assertEquals(200, response.status)
            assertArrayEquals("payload".toByteArray(), response.body.bytes())
            response.body.close()
        }

    @Test
    fun streamingChunkingDeliversAllBytes() =
        runBlocking {
            handler = { _, _ -> 200 to "a".repeat(100_000) }
            val client = OkHttpWireClient()
            val response = client.open(WireRequest("GET", "$base/big", emptyMap(), null))
            val seen = ArrayList<ByteArray>()
            var total = 0
            response.body.forEachChunk { chunk ->
                seen += chunk
                total += chunk.size
                true
            }
            assertEquals(100_000, total)
            assertTrue(seen.size > 1)
            val all = ByteArray(total)
            var offset = 0
            seen.forEach { chunk ->
                chunk.copyInto(all, offset)
                offset += chunk.size
            }
            assertArrayEquals("a".repeat(100_000).toByteArray(), all)
            response.body.close()
        }

    @Test
    fun non2xxStatusIsPassedThrough() =
        runBlocking {
            handler = { _, _ -> 404 to "nope" }
            val client = OkHttpWireClient()
            val response = client.open(WireRequest("GET", "$base/missing", emptyMap(), null))
            assertEquals(404, response.status)
            response.body.close()
        }

    @Test
    fun oversizedBodyFailsTheCap() =
        runBlocking {
            handler = { _, _ -> 200 to "x".repeat(1_024) }
            val client = OkHttpWireClient(maxBodyBytes = 64)
            val response = client.open(WireRequest("GET", "$base/huge", emptyMap(), null))
            assertThrows(IOException::class.java) {
                runBlocking { response.body.bytes() }
            }
            response.body.close()
        }

    @Test
    fun connectionRefusedThrowsIo() {
        runBlocking {
            val client = OkHttpWireClient(connectTimeoutMillis = 2_000L)
            assertThrows(IOException::class.java) {
                runBlocking {
                    client.open(WireRequest("GET", "http://127.0.0.1:1", emptyMap(), null))
                }
            }
        }
    }
}
