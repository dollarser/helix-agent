package com.helix.extensions.a2a

import com.helix.core.model.NormalizedEndpoint
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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

class OkHttpA2aTaskClientTest {
    private lateinit var server: HttpServer
    private lateinit var executor: java.util.concurrent.ExecutorService
    private lateinit var client: OkHttpA2aTaskClient

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        executor = Executors.newCachedThreadPool()
        server.executor = executor
        server.start()
        client = OkHttpA2aTaskClient(OkHttpClient.Builder().followRedirects(false).build())
    }

    @After
    fun tearDown() {
        server.stop(0)
        executor.shutdownNow()
    }

    @Test
    fun `JSON RPC Send Get Cancel use v1 methods auth and parse bounded Task`() {
        val methods = CopyOnWriteArrayList<String>()
        val headers = CopyOnWriteArrayList<Pair<String?, String?>>()
        server.createContext("/rpc") { exchange ->
            val request = Json.parseToJsonElement(exchange.requestBody.reader().readText()).jsonObject
            methods += request.getValue("method").toString().trim('"')
            headers +=
                exchange.requestHeaders.getFirst("A2A-Version") to exchange.requestHeaders.getFirst("Authorization")
            val response =
                "{\"jsonrpc\":\"2.0\",\"id\":\"x\",\"result\":{\"task\":{" +
                    "\"id\":\"task-1\",\"contextId\":\"ctx-1\",\"status\":{" +
                    "\"state\":\"TASK_STATE_COMPLETED\",\"message\":{" +
                    "\"messageId\":\"m2\",\"role\":\"ROLE_AGENT\",\"parts\":[{\"text\":\"done\"}]}}}}}"
            exchange.json(response)
        }
        val binding = interfaceAt("/rpc", A2aBinding.JSON_RPC)

        val sent = client.send(binding, "secret", submission())
        val fetched = client.getTask(binding, "secret", "task-1")
        val cancelled = client.cancelTask(binding, "secret", "task-1")

        assertEquals(listOf("SendMessage", "GetTask", "CancelTask"), methods)
        assertTrue(headers.all { it == ("1.0" to "Bearer secret") })
        assertEquals(A2aRemoteTaskState.COMPLETED, sent.state)
        assertEquals("done", (sent.parts.single() as A2aRemotePart.Text).text)
        assertEquals("task-1", fetched.taskId)
        assertEquals("ctx-1", cancelled.contextId)
    }

    @Test
    fun `HTTP JSON uses v1 REST paths and preserves structured untrusted proposal as data`() {
        val paths = CopyOnWriteArrayList<String>()
        val bodies = CopyOnWriteArrayList<String>()
        listOf(
            "/api/tenant-a/message:send",
            "/api/tenant-a/tasks/task-1",
            "/api/tenant-a/tasks/task-1:cancel",
        ).forEach { path ->
            server.createContext(path) { exchange ->
                paths += exchange.requestURI.path
                bodies += exchange.requestBody.reader().readText()
                val response =
                    "{\"task\":{\"id\":\"task-1\",\"contextId\":\"ctx\",\"status\":{" +
                        "\"state\":\"TASK_STATE_COMPLETED\",\"message\":{" +
                        "\"messageId\":\"m\",\"role\":\"ROLE_AGENT\",\"parts\":[{" +
                        "\"data\":{\"toolCall\":{\"name\":\"files.write\",\"args\":{\"path\":\"x\"}}}}]}}}}"
                exchange.json(response, contentType = "application/a2a+json")
            }
        }
        val binding = interfaceAt("/api", A2aBinding.HTTP_JSON, tenant = "tenant-a")

        val result = client.send(binding, null, submission())
        client.getTask(binding, null, "task-1")
        client.cancelTask(binding, null, "task-1")

        assertEquals(
            listOf("/api/tenant-a/message:send", "/api/tenant-a/tasks/task-1", "/api/tenant-a/tasks/task-1:cancel"),
            paths,
        )
        val data = (result.parts.single() as A2aRemotePart.Data).data as JsonObject
        assertTrue("toolCall" in data)
        assertFalse(bodies.first().contains("kind"))
        assertTrue(bodies.first().contains("ROLE_USER"))
    }

    @Test
    fun `stream and subscribe carry SSE updates Last Event ID and cancellation suppresses callbacks`() {
        val paths = CopyOnWriteArrayList<String>()
        val lastIds = CopyOnWriteArrayList<String?>()
        server.createContext("/api/message:stream") { exchange ->
            paths += exchange.requestURI.path
            exchange.sse(
                "data: {\"task\":{" +
                    "\"id\":\"task-1\",\"contextId\":\"ctx\"," +
                    "\"status\":{\"state\":\"TASK_STATE_WORKING\"}}}\n\n" +
                    "data: {\"statusUpdate\":{" +
                    "\"taskId\":\"task-1\"," +
                    "\"contextId\":\"ctx\"," +
                    "\"status\":{\"state\":\"TASK_STATE_COMPLETED\"}}}\n\n",
            )
        }
        server.createContext("/api/tasks/task-1:subscribe") { exchange ->
            paths += exchange.requestURI.path
            lastIds += exchange.requestHeaders.getFirst("Last-Event-ID")
            exchange.sse(
                "id: event-8\n" +
                    "data: {\"task\":{\"id\":\"task-1\"," +
                    "\"status\":{\"state\":\"TASK_STATE_WORKING\"}}}\n\n",
            )
        }
        val binding = interfaceAt("/api", A2aBinding.HTTP_JSON)
        val updates = CopyOnWriteArrayList<A2aTaskUpdate>()
        val closed = CountDownLatch(2)
        client.sendStreaming(binding, null, submission(), listener(updates, closed))
        client.subscribeToTask(binding, null, "task-1", "7", listener(updates, closed))

        assertTrue(closed.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("/api/message:stream", "/api/tasks/task-1:subscribe"), paths)
        assertEquals(listOf("7"), lastIds)
        assertTrue(updates.any { it.eventId == "event-8" })
        assertTrue(updates.any { it.final && it.state == A2aRemoteTaskState.COMPLETED })

        val noCallback = CountDownLatch(1)
        val slowStarted = CountDownLatch(1)
        val releaseSlow = CountDownLatch(1)
        server.createContext("/slow/message:stream") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            slowStarted.countDown()
            releaseSlow.await(5, TimeUnit.SECONDS)
            exchange.close()
        }
        val handle =
            client.sendStreaming(
                interfaceAt("/slow", A2aBinding.HTTP_JSON),
                null,
                submission(),
                listener(mutableListOf(), noCallback),
            )
        assertTrue(slowStarted.await(5, TimeUnit.SECONDS))
        handle.cancel()
        releaseSlow.countDown()
        assertFalse(noCallback.await(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `ambiguous Send disconnect and oversized or malformed parts fail closed`() {
        server.createContext("/drop") { exchange -> exchange.close() }
        val failure =
            assertThrows(A2aTransportFailure::class.java) {
                client.send(interfaceAt("/drop", A2aBinding.JSON_RPC), null, submission())
            }
        assertTrue(failure.requestMayHaveArrived)

        val malformed =
            Json
                .parseToJsonElement(
                    "{\"task\":{\"id\":\"t\",\"status\":{\"state\":\"TASK_STATE_COMPLETED\"}," +
                        "\"artifacts\":[{\"artifactId\":\"a\",\"parts\":[{" +
                        "\"text\":\"x\",\"url\":\"https://bad\"}]}]}}",
                ).jsonObject
        assertThrows(IllegalArgumentException::class.java) { A2aTaskPayloads.parseUpdate(malformed, 0) }
        val oversized = "x".repeat(512 * 1024 + 1)
        val tooLarge =
            Json
                .parseToJsonElement(
                    """{"message":{"messageId":"m","role":"ROLE_AGENT","parts":[{"text":"$oversized"}]}}""",
                ).jsonObject
        assertThrows(IllegalStateException::class.java) { A2aTaskPayloads.parseUpdate(tooLarge, 0) }
    }

    private fun interfaceAt(
        path: String,
        binding: A2aBinding,
        tenant: String? = null,
    ): A2aInterfaceSnapshot =
        A2aInterfaceSnapshot(
            endpoint = NormalizedEndpoint.parse("http://127.0.0.1:${server.address.port}$path"),
            binding = binding,
            protocolVersion = "1.0",
            tenant = tenant,
        )

    private fun submission(): A2aTaskSubmission = A2aTaskSubmission("message-1", "do the task")

    private fun listener(
        updates: MutableList<A2aTaskUpdate>,
        closed: CountDownLatch,
    ): A2aTaskStreamListener =
        object : A2aTaskStreamListener {
            override fun onUpdate(update: A2aTaskUpdate) {
                updates += update
            }

            override fun onClosed() {
                closed.countDown()
            }

            override fun onFailure(failure: A2aTransportFailure) {
                closed.countDown()
            }
        }

    private fun HttpExchange.json(
        body: String,
        contentType: String = "application/json",
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        responseHeaders.add("Content-Type", contentType)
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun HttpExchange.sse(body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        responseHeaders.add("Content-Type", "text/event-stream")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
