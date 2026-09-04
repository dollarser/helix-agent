package com.helix.spikes.a2a.minimal

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OkHttpA2aClientFacadeTest {
    private lateinit var server: HttpServer
    private lateinit var executor: java.util.concurrent.ExecutorService
    private lateinit var facade: OkHttpA2aClientFacade

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        executor = Executors.newCachedThreadPool()
        server.executor = executor
        server.start()
        facade = OkHttpA2aClientFacade(OkHttpClient(), allowLoopbackHttp = true)
    }

    @After
    fun tearDown() {
        server.stop(0)
        executor.shutdownNow()
    }

    @Test
    fun `json rpc and http json carry bounded large payloads`() {
        val contentTypes = CopyOnWriteArrayList<String>()
        server.createContext("/large") { exchange ->
            val body = exchange.requestBody.readAllBytes()
            contentTypes += exchange.requestHeaders.getFirst("Content-Type")
            exchange.jsonResponse("{\"received\":${body.size}}")
        }
        val payload = "{\"message\":\"${"x".repeat(1024 * 1024)}\"}"

        val jsonRpc = facade.execute(request("/large", A2aHttpBinding.JSON_RPC, payload))
        val httpJson = facade.execute(request("/large", A2aHttpBinding.HTTP_JSON, payload))

        assertTrue(jsonRpc.body.contains("received"))
        assertEquals(jsonRpc.body, httpJson.body)
        assertTrue(contentTypes[0].startsWith("application/json"))
        assertTrue(contentTypes[1].startsWith("application/a2a+json"))
    }

    @Test
    fun `sse reconnect carries last event id and cancellation suppresses callbacks`() {
        val seenLastIds = CopyOnWriteArrayList<String?>()
        server.createContext("/events") { exchange ->
            val lastId = exchange.requestHeaders.getFirst("Last-Event-ID")
            seenLastIds += lastId
            val nextId = if (lastId == null) "1" else "2"
            exchange.sseResponse("id: $nextId\nevent: task\ndata: {\"sequence\":$nextId}\n\n")
        }
        val events = CopyOnWriteArrayList<A2aSseEvent>()
        val firstClosed = CountDownLatch(1)
        facade.stream(request("/events", A2aHttpBinding.JSON_RPC, "{}"), listener(events, firstClosed))
        assertTrue(firstClosed.await(5, TimeUnit.SECONDS))

        val secondClosed = CountDownLatch(1)
        facade.stream(
            request("/events", A2aHttpBinding.JSON_RPC, "{}", mapOf("Last-Event-ID" to "1")),
            listener(events, secondClosed),
        )
        assertTrue(secondClosed.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("1", "2"), events.map { it.id })
        assertEquals(listOf(null, "1"), seenLastIds)

        val slowStarted = CountDownLatch(1)
        val releaseSlow = CountDownLatch(1)
        server.createContext("/slow") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            slowStarted.countDown()
            releaseSlow.await(5, TimeUnit.SECONDS)
            exchange.close()
        }
        val cancelledCallbacks = CopyOnWriteArrayList<String>()
        val cancelledCallback = CountDownLatch(1)
        val handle =
            facade.stream(
                request("/slow", A2aHttpBinding.JSON_RPC, "{}"),
                object : A2aStreamListener {
                    override fun onEvent(event: A2aSseEvent) {
                        cancelledCallbacks += "event"
                        cancelledCallback.countDown()
                    }

                    override fun onClosed() {
                        cancelledCallbacks += "closed"
                        cancelledCallback.countDown()
                    }

                    override fun onFailure(failure: java.io.IOException) {
                        cancelledCallbacks += "failure"
                        cancelledCallback.countDown()
                    }
                },
            )
        assertTrue(slowStarted.await(5, TimeUnit.SECONDS))
        handle.cancel()
        releaseSlow.countDown()
        assertFalse(cancelledCallback.await(200, TimeUnit.MILLISECONDS))
        assertFalse(cancelledCallbacks.isNotEmpty())
    }

    @Test
    fun `cleartext non loopback invalid json and oversized response fail closed`() {
        val productionFacade = OkHttpA2aClientFacade(OkHttpClient())
        assertFails { productionFacade.execute(A2aHttpRequest("http://example.com", A2aHttpBinding.JSON_RPC, "{}")) }
        assertFails { facade.execute(request("/unused", A2aHttpBinding.JSON_RPC, "not-json")) }
        server.createContext("/oversized") { exchange ->
            exchange.sendResponseHeaders(200, 2L * 1024 * 1024 + 1)
            exchange.close()
        }
        assertFails { facade.execute(request("/oversized", A2aHttpBinding.HTTP_JSON, "{}")) }
    }

    private fun listener(
        events: MutableList<A2aSseEvent>,
        closed: CountDownLatch,
    ): A2aStreamListener =
        object : A2aStreamListener {
            override fun onEvent(event: A2aSseEvent) {
                events += event
            }

            override fun onClosed() {
                closed.countDown()
            }

            override fun onFailure(failure: java.io.IOException): Unit = throw AssertionError(failure)
        }

    private fun request(
        path: String,
        binding: A2aHttpBinding,
        payload: String,
        headers: Map<String, String> = emptyMap(),
    ): A2aHttpRequest = A2aHttpRequest("http://127.0.0.1:${server.address.port}$path", binding, payload, headers)

    private fun HttpExchange.jsonResponse(body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun HttpExchange.sseResponse(body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        responseHeaders.add("Content-Type", "text/event-stream")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun assertFails(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: Exception) {
            failed = true
        }
        assertTrue(failed)
    }
}
