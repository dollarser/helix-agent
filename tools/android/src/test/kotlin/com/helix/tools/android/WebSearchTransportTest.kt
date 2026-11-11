package com.helix.tools.android

import com.helix.core.model.NoCancellation
import com.helix.core.model.SafetyProfile
import com.helix.core.policy.NetworkOriginScope
import com.sun.net.httpserver.HttpServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class WebSearchTransportTest {
    @Test
    fun jsonPostUsesPolicyAndNeverReplaysRedirectOrServerError() {
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            calls.incrementAndGet()
            assertEquals("POST", exchange.requestMethod)
            exchange.responseHeaders.add("Location", "/again")
            exchange.sendResponseHeaders(307, -1)
            exchange.close()
        }
        server.start()
        try {
            val port = server.address.port
            val request = request(port)
            val refused = PinnedWebFetchTransport(policy(EgressPolicy(SafetyProfile.STANDARD, emptySet())))
            assertThrows(WebFetchFailure::class.java) { refused.postJson(request, deadline(), NoCancellation) }
            assertEquals(0, calls.get())
            val allowed = PinnedWebFetchTransport(policy(allowed(port)))
            assertEquals(
                "provider-http-307",
                assertThrows(WebFetchFailure::class.java) {
                    allowed.postJson(request, deadline(), NoCancellation)
                }.code,
            )
            assertEquals(1, calls.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun jsonBodyIsBoundedAndUnsupportedEncodingFails() {
        for ((encoding, size, code) in listOf(
            Triple("br", 10, "unsupported-content-encoding"),
            Triple("identity", WEB_BODY_LIMIT + 1, "provider-response-too-large"),
        )) {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.responseHeaders.add("Content-Encoding", encoding)
                exchange.sendResponseHeaders(200, size.toLong())
                runCatching { exchange.responseBody.use { it.write(ByteArray(size)) } }
                exchange.close()
            }
            server.start()
            try {
                val port = server.address.port
                val transport = PinnedWebFetchTransport(policy(allowed(port)))
                assertEquals(
                    code,
                    assertThrows(WebFetchFailure::class.java) {
                        transport.postJson(request(port), deadline(), NoCancellation)
                    }.code,
                )
            } finally {
                server.stop(0)
            }
        }
    }

    @Test
    fun productionRequestHasFixedBasicQuotaParametersAndHeaderOnlySecret() {
        val request =
            WebSearchTool.request(
                "fixture query",
                WebSearchConfiguration(WebSearchProvider.TAVILY, "synthetic-key"),
            )
        assertEquals("https://api.tavily.com/search", request.url.toString())
        assertEquals("Bearer synthetic-key", request.header("Authorization"))
        val buffer = okio.Buffer()
        requireNotNull(request.body).writeTo(buffer)
        val body =
            kotlinx.serialization.json.Json
                .parseToJsonElement(buffer.readUtf8())
                as kotlinx.serialization.json.JsonObject
        assertEquals(kotlinx.serialization.json.JsonPrimitive("basic"), body["search_depth"])
        assertEquals(kotlinx.serialization.json.JsonPrimitive(false), body["auto_parameters"])
        assertEquals(kotlinx.serialization.json.JsonPrimitive(false), body["include_raw_content"])
        org.junit.Assert.assertFalse(body.toString().contains("synthetic-key"))
    }

    @Test
    fun httpFailuresAndConfigurationRevocationDoNotRetry() {
        for (status in listOf(429, 500)) {
            val calls = AtomicInteger()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                calls.incrementAndGet()
                assertEquals("Bearer synthetic-key", exchange.requestHeaders.getFirst("Authorization"))
                exchange.sendResponseHeaders(status, -1)
                exchange.close()
            }
            server.start()
            try {
                val port = server.address.port
                val transport = PinnedWebFetchTransport(policy(allowed(port)))
                val request =
                    WebSearchTool
                        .request("fixture", WebSearchConfiguration(WebSearchProvider.TAVILY, "synthetic-key"))
                        .newBuilder()
                        .url("http://127.0.0.1:$port/")
                        .build()
                assertEquals(
                    "configuration-changed-before-send",
                    assertThrows(WebFetchFailure::class.java) {
                        transport.postJson(request, deadline(), NoCancellation) {
                            throw WebFetchFailure("configuration-changed-before-send")
                        }
                    }.code,
                )
                assertEquals(0, calls.get())
                assertEquals(
                    "provider-http-$status",
                    assertThrows(WebFetchFailure::class.java) {
                        transport.postJson(request, deadline(), NoCancellation)
                    }.code,
                )
                assertEquals(1, calls.get())
            } finally {
                server.stop(0)
            }
        }
    }

    @Test
    fun activePostCancellationClosesReadWithoutReplay() {
        val cancelled =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        val calls = AtomicInteger()
        val received = java.util.concurrent.CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            calls.incrementAndGet()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write('{'.code)
            exchange.responseBody.flush()
            received.countDown()
            Thread.sleep(300)
            exchange.close()
        }
        server.start()
        val cancel =
            object : com.helix.core.model.CancelSignal {
                override fun isCancelled() = cancelled.get()
            }
        val pool =
            java.util.concurrent.Executors
                .newSingleThreadExecutor()
        try {
            val port = server.address.port
            val transport = PinnedWebFetchTransport(policy(allowed(port)))
            val future = pool.submit<ByteArray> { transport.postJson(request(port), deadline(), cancel) }
            org.junit.Assert.assertTrue(received.await(3, java.util.concurrent.TimeUnit.SECONDS))
            cancelled.set(true)
            assertThrows(java.util.concurrent.ExecutionException::class.java) {
                future.get(3, java.util.concurrent.TimeUnit.SECONDS)
            }
            assertEquals(1, calls.get())
        } finally {
            pool.shutdownNow()
            server.stop(0)
        }
    }

    @Test
    fun deepSeekRequestUsesOfficialEndpointAndNoExtraContentOrModelCharge() {
        val request =
            WebSearchTool.request(
                "fixture",
                WebSearchConfiguration(WebSearchProvider.DEEPSEEK, "synthetic-key"),
            )
        assertEquals("https://api.deepseek.com/v1/tools/search", request.url.toString())
        assertEquals("Bearer synthetic-key", request.header("Authorization"))
        val buffer = okio.Buffer()
        requireNotNull(request.body).writeTo(buffer)
        val body =
            kotlinx.serialization.json.Json.parseToJsonElement(
                buffer.readUtf8(),
            ) as kotlinx.serialization.json.JsonObject
        assertEquals(setOf("query", "count", "need_content"), body.keys)
        assertEquals(kotlinx.serialization.json.JsonPrimitive(5), body["count"])
        assertEquals(kotlinx.serialization.json.JsonPrimitive(false), body["need_content"])
        org.junit.Assert.assertFalse(body.toString().contains("synthetic-key"))
    }

    private fun allowed(port: Int) =
        EgressPolicy(
            SafetyProfile.ADVANCED,
            setOf(NetworkOriginScope("127.0.0.1", port)),
        )

    private fun policy(value: EgressPolicy) =
        object : EgressPolicyProvider {
            override fun current() = value
        }

    private fun request(port: Int) =
        Request
            .Builder()
            .url("http://127.0.0.1:$port/")
            .header("Accept-Encoding", "identity")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()

    private fun deadline() = System.currentTimeMillis() + 10_000
}
