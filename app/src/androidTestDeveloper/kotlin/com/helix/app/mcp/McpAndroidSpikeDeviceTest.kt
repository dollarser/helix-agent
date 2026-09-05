package com.helix.app.mcp

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.MainActivity
import com.helix.core.model.SafetyProfile
import com.helix.core.policy.NetworkOriginScope
import com.helix.extensions.mcp.McpClients
import com.helix.extensions.mcp.McpCredentialLookup
import com.helix.extensions.mcp.McpHandshakeService
import com.helix.extensions.mcp.McpHostResolver
import com.helix.extensions.mcp.McpServerConfig
import com.helix.extensions.mcp.McpSsrfEndpointGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * HXA-070 Android acceptance for the pinned MCP Kotlin SDK + Ktor OkHttp transport.
 *
 * The developer variant intentionally permits user-confirmed cleartext LAN/loopback traffic, so
 * this fixture exercises the real Android network stack without weakening the consumer manifest.
 */
@RunWith(AndroidJUnit4::class)
@Suppress("TooManyFunctions") // One acceptance class keeps the HXA-070 device matrix auditable.
class McpAndroidSpikeDeviceTest {
    @Test
    fun sessionRemainsUsableAcrossActivityBackgroundAndForeground() =
        runBlocking {
            AndroidMcpFixture().use { fixture ->
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    val session = McpClients.sdk("helix-android-spike", "1").connect(fixture.endpoint)
                    try {
                        scenario.moveToState(Lifecycle.State.CREATED)
                        session.ping()
                        scenario.moveToState(Lifecycle.State.RESUMED)
                        session.ping()
                        assertEquals(2, fixture.pingCount.get())
                    } finally {
                        session.close()
                    }
                }
            }
        }

    @Test
    fun initializeAndPingUseNegotiatedProtocolHeader() =
        runBlocking {
            AndroidMcpFixture().use { fixture ->
                val session = McpClients.sdk("helix-android-spike", "1").connect(fixture.endpoint)
                try {
                    assertEquals("fixture", session.server.name)
                    assertEquals("2025-03-26", session.server.negotiatedProtocolVersion)
                    session.ping()
                    assertEquals(1, fixture.pingCount.get())
                    assertTrue(fixture.protocolVersions.contains("2025-03-26"))
                } finally {
                    session.close()
                }
            }
        }

    @Test
    fun freshFacadeReconnectsAfterClosedAndroidSession() =
        runBlocking {
            AndroidMcpFixture().use { fixture ->
                McpClients.sdk("helix-android-spike", "1").connect(fixture.endpoint).close()
                McpClients.sdk("helix-android-spike", "1").connect(fixture.endpoint).close()

                assertEquals(2, fixture.initializeCount.get())
            }
        }

    @Test
    fun cancelledPingDoesNotPoisonAndroidSession() =
        runBlocking {
            AndroidMcpFixture(firstPingDelayMillis = 1_000).use { fixture ->
                val session = McpClients.sdk("helix-android-spike", "1").connect(fixture.endpoint)
                try {
                    val failure = runCatching { withTimeout(100) { session.ping() } }.exceptionOrNull()
                    assertTrue(failure is CancellationException)
                    session.ping()
                    assertEquals(2, fixture.pingCount.get())
                } finally {
                    session.close()
                }
            }
        }

    @Test
    fun oneMiBUnknownInitializeFieldStaysBehindFacade() =
        runBlocking {
            AndroidMcpFixture(extraInitializeFieldBytes = 1024 * 1024).use { fixture ->
                val session = McpClients.sdk("helix-android-spike", "1").connect(fixture.endpoint)
                try {
                    assertEquals("fixture", session.server.name)
                    assertTrue(session.server.toString().length < 256)
                } finally {
                    session.close()
                }
            }
        }

    @Test
    fun streamableHttpReconnectsSseWithLastEventIdOnAndroid() =
        runBlocking {
            AndroidMcpFixture(enableSseReconnect = true).use { fixture ->
                val session = McpClients.sdk("helix-android-spike", "1").connect(fixture.endpoint)
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
    fun unauthorizedInitializeFailsClosedOnAndroid() =
        runBlocking {
            AndroidMcpFixture(rejectInitializeUnauthorized = true).use { fixture ->
                val failure =
                    runCatching {
                        McpClients.sdk("helix-android-spike", "1").connect(fixture.endpoint)
                    }.exceptionOrNull()

                assertTrue("HTTP 401 must not create an MCP session", failure != null)
                assertEquals(1, fixture.initializeCount.get())
            }
        }

    @Test
    fun initializeWithoutWireProtocolVersionFailsClosedOnAndroid() =
        runBlocking {
            AndroidMcpFixture(omitInitializeProtocolVersion = true).use { fixture ->
                val failure =
                    runCatching {
                        McpClients.sdk("helix-android-spike", "1").connect(fixture.endpoint)
                    }.exceptionOrNull()

                assertTrue("non-compliant initialize unexpectedly connected", failure != null)
                assertTrue(
                    "missing wire version failure was not preserved: $failure",
                    generateSequence(failure) { it.cause }.any {
                        "initialize protocolVersion missing" in it.message.orEmpty()
                    },
                )
            }
        }

    @Test
    fun bearerHandshakeUsesPinnedLoopbackAndDoesNotLeakCredential() =
        runBlocking {
            AndroidMcpFixture().use { fixture ->
                val config =
                    McpServerConfig.disabled(
                        id = "android-auth-fixture",
                        endpointUrl = fixture.endpoint,
                        bearerSecretAlias = "mcp.android.fixture",
                    )
                val gate =
                    McpSsrfEndpointGate(
                        profileProvider = { SafetyProfile.ADVANCED },
                        lanScopesProvider = { setOf(NetworkOriginScope("127.0.0.1", fixture.port)) },
                        resolver = McpHostResolver { listOf(byteArrayOf(127, 0, 0, 1)) },
                    )
                val snapshot =
                    McpHandshakeService(
                        credentials = McpCredentialLookup { "fixture-secret" },
                        endpointGate = gate,
                        clientName = "helix-android-spike",
                        clientVersion = "1",
                    ).testConnection(config)

                assertEquals("fixture", snapshot.identity.name)
                assertTrue(fixture.authorizationHeaders.isNotEmpty())
                assertTrue(fixture.authorizationHeaders.all { it == "Bearer fixture-secret" })
                assertTrue("credential crossed the handshake boundary", "fixture-secret" !in snapshot.toString())
            }
        }

    @Test
    fun tlsHandshakeFailureFailsClosedOnAndroid() =
        runBlocking {
            TlsRejectingFixture().use { fixture ->
                val failure =
                    runCatching {
                        withTimeout(5_000) {
                            McpClients.sdk("helix-android-spike", "1").connect(fixture.endpoint)
                        }
                    }.exceptionOrNull()

                assertTrue("TLS failure must not create an MCP session", failure != null)
                assertTrue("TLS failure unexpectedly waited for the test timeout", failure !is CancellationException)
                assertTrue("HTTPS fixture did not observe a connection", fixture.awaitConnection())
            }
        }

    @Test
    fun chunkedInitializeResponseOverWireLimitFailsBeforeSdkDecode() =
        runBlocking {
            AndroidMcpFixture(
                extraInitializeFieldBytes = 17 * 1024 * 1024,
                chunkedInitializeResponse = true,
            ).use { fixture ->
                val failure =
                    runCatching {
                        withTimeout(10_000) {
                            McpClients.sdk("helix-android-spike", "1").connect(fixture.endpoint)
                        }
                    }.exceptionOrNull()

                assertTrue("oversized chunked response unexpectedly connected", failure != null)
                assertTrue(
                    "wire-limit failure was not preserved: $failure",
                    generateSequence(failure) { it.cause }.any { "MCP HTTP response exceeds" in it.message.orEmpty() },
                )
            }
        }

    @Test
    fun oversizedSseEventClosesAndroidStreamAtWireBoundary() =
        runBlocking {
            AndroidMcpFixture(oversizedSseEvent = true).use { fixture ->
                val session = McpClients.sdk("helix-android-spike", "1").connect(fixture.endpoint)
                try {
                    assertTrue(
                        "client did not close the oversized SSE event at the wire boundary",
                        fixture.awaitOversizedSseClosedByClient(),
                    )
                } finally {
                    session.close()
                }
            }
        }
}

@Suppress("TooManyFunctions") // A single bounded socket fixture keeps all MCP wire behavior together.
private class AndroidMcpFixture(
    private val extraInitializeFieldBytes: Int = 0,
    private val firstPingDelayMillis: Long = 0,
    private val enableSseReconnect: Boolean = false,
    private val rejectInitializeUnauthorized: Boolean = false,
    private val chunkedInitializeResponse: Boolean = false,
    private val oversizedSseEvent: Boolean = false,
    private val omitInitializeProtocolVersion: Boolean = false,
) : AutoCloseable {
    private val server = ServerSocket(0)
    private val clients = Executors.newCachedThreadPool()
    private val acceptor =
        Thread(::acceptConnections, "hxa070-mcp-accept").apply {
            isDaemon = true
            start()
        }
    val pingCount = AtomicInteger(0)
    val initializeCount = AtomicInteger(0)
    val sseGetCount = AtomicInteger(0)
    val lastEventIds = CopyOnWriteArrayList<String?>()
    val authorizationHeaders = CopyOnWriteArrayList<String?>()
    val protocolVersions = CopyOnWriteArrayList<String?>()
    val port: Int = server.localPort
    val endpoint = "http://127.0.0.1:$port/mcp"
    private val secondSseConnection = CountDownLatch(1)
    private val oversizedSseClosedByClient = CountDownLatch(1)

    private fun acceptConnections() {
        while (!server.isClosed) {
            try {
                val socket = server.accept()
                dispatch(socket)
            } catch (_: java.net.SocketException) {
                if (!server.isClosed) throw AssertionError("MCP fixture accept failed")
            }
        }
    }

    private fun dispatch(socket: Socket) {
        try {
            clients.execute { handle(socket) }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            socket.close()
            if (!server.isClosed) throw AssertionError("MCP fixture worker rejected a live connection")
        }
    }

    @Suppress("ReturnCount") // Early exits keep each one-request fixture connection explicit.
    private fun handle(socket: Socket) {
        socket.use { connection ->
            val request = readRequest(connection) ?: return
            if (request.method == "GET") {
                handleSseGet(connection, request)
                return
            }
            require(request.method == "POST") { "unexpected MCP fixture method: ${request.method}" }
            val method = METHOD_PATTERN.find(request.body)?.groupValues?.get(1)
            val id = ID_PATTERN.find(request.body)?.groupValues?.get(1)
            authorizationHeaders += request.headers["authorization"]
            protocolVersions += request.headers["mcp-protocol-version"]
            when (method) {
                "initialize" -> {
                    initializeCount.incrementAndGet()
                    if (rejectInitializeUnauthorized) {
                        respondEmpty(connection, 401)
                        return
                    }
                    if (chunkedInitializeResponse) {
                        respondChunkedInitialize(connection, id)
                        return
                    }
                    val padding = "x".repeat(extraInitializeFieldBytes)
                    respondJson(
                        connection,
                        200,
                        buildString {
                            append("""{"jsonrpc":"2.0","id":$id,"result":{""")
                            if (!omitInitializeProtocolVersion) {
                                append(""""protocolVersion":"2025-03-26",""")
                            } else {
                                append(""""_decoy":{"protocolVersion":"fake"},""")
                            }
                            append(""""capabilities":{"tools":{}},"serverInfo":{"name":"fixture","version":"1.0"},""")
                            append(""""_padding":"$padding"}}""")
                        },
                        sessionId = "fixture-session",
                    )
                }

                "notifications/initialized" -> {
                    respondEmpty(connection, 202)
                }

                "ping" -> {
                    handlePing(connection, id)
                }

                "tools/list" -> {
                    respondJson(connection, 200, """{"jsonrpc":"2.0","id":$id,"result":{"tools":[]}}""")
                }

                else -> {
                    respondEmpty(connection, 404)
                }
            }
        }
    }

    fun awaitSecondSseConnection(): Boolean = secondSseConnection.await(5, TimeUnit.SECONDS)

    fun awaitOversizedSseClosedByClient(): Boolean = oversizedSseClosedByClient.await(10, TimeUnit.SECONDS)

    @Suppress("ReturnCount") // Each fixture mode owns one explicit response and closes the request.
    private fun handleSseGet(
        connection: Socket,
        request: Request,
    ) {
        if (!enableSseReconnect && !oversizedSseEvent) {
            respondEmpty(connection, 405)
            return
        }

        val attempt = sseGetCount.incrementAndGet()
        lastEventIds += request.headers["last-event-id"]
        if (oversizedSseEvent) {
            if (attempt == 1) {
                respondOversizedSseEvent(connection)
            } else {
                respondEmpty(connection, 405)
            }
            return
        }
        if (attempt > 1) {
            secondSseConnection.countDown()
            respondEmpty(connection, 405)
            return
        }

        val response =
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/event-stream\r\n" +
                "Connection: close\r\n\r\n" +
                "id: event-1\ndata:\n\n"
        connection.getOutputStream().apply {
            write(response.toByteArray(StandardCharsets.US_ASCII))
            flush()
        }
    }

    private fun respondOversizedSseEvent(connection: Socket) {
        val headers =
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/event-stream\r\n" +
                "Connection: keep-alive\r\n\r\n"
        val chunk = ByteArray(CHUNK_BYTES) { 'x'.code.toByte() }
        try {
            connection.getOutputStream().apply {
                write(headers.toByteArray(StandardCharsets.US_ASCII))
                write("data: ".toByteArray(StandardCharsets.US_ASCII))
                repeat((17 * 1024 * 1024) / chunk.size) {
                    write(chunk)
                    flush()
                }
                flush()
            }
            connection.soTimeout = 8_000
            if (connection.getInputStream().read() < 0) {
                oversizedSseClosedByClient.countDown()
            }
        } catch (_: java.net.SocketTimeoutException) {
            // A client without the event ceiling leaves this deliberately incomplete event open.
        } catch (_: java.io.IOException) {
            oversizedSseClosedByClient.countDown()
        }
    }

    private fun handlePing(
        connection: Socket,
        id: String?,
    ) {
        if (pingCount.incrementAndGet() == 1 && firstPingDelayMillis > 0) {
            try {
                Thread.sleep(firstPingDelayMillis)
            } catch (_: InterruptedException) {
                // Fixture shutdown after client cancellation: the cancelled request must not
                // crash the instrumented app process or emit a late response.
                return
            }
        }
        respondJson(connection, 200, """{"jsonrpc":"2.0","id":$id,"result":{}}""")
    }

    private fun readRequest(socket: Socket): Request? {
        val input = BufferedInputStream(socket.getInputStream())
        val headerBytes = ArrayList<Byte>()
        var matched = 0
        val delimiter = byteArrayOf(13, 10, 13, 10)
        while (headerBytes.size <= MAX_HEADER_BYTES) {
            val next = input.read()
            if (next < 0) return null
            val byte = next.toByte()
            headerBytes += byte
            matched =
                if (byte == delimiter[matched]) {
                    matched + 1
                } else if (byte == delimiter[0]) {
                    1
                } else {
                    0
                }
            if (matched == delimiter.size) break
        }
        require(headerBytes.size <= MAX_HEADER_BYTES) { "MCP fixture request header too large" }
        val header = headerBytes.toByteArray().toString(StandardCharsets.US_ASCII)
        val lines = header.split("\r\n")
        val method = lines.first().substringBefore(' ')
        val headers =
            lines
                .drop(1)
                .mapNotNull { line ->
                    val separator = line.indexOf(':')
                    if (separator <=
                        0
                    ) {
                        null
                    } else {
                        line.substring(0, separator).lowercase(Locale.ROOT) to
                            line.substring(separator + 1).trim()
                    }
                }.toMap()
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        require(contentLength in 0..MAX_REQUEST_BYTES) { "MCP fixture request body too large" }
        val body = ByteArray(contentLength)
        var offset = 0
        while (offset < body.size) {
            val count = input.read(body, offset, body.size - offset)
            require(count >= 0) { "MCP fixture request body truncated" }
            offset += count
        }
        return Request(method, headers, body.toString(StandardCharsets.UTF_8))
    }

    private fun respondJson(
        socket: Socket,
        status: Int,
        body: String,
        sessionId: String? = null,
        chunked: Boolean = false,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val headers =
            buildString {
                append("HTTP/1.1 $status ${statusText(status)}\r\n")
                append("Content-Type: application/json\r\n")
                sessionId?.let { append("mcp-session-id: $it\r\n") }
                if (chunked) {
                    append("Transfer-Encoding: chunked\r\n")
                } else {
                    append("Content-Length: ${bytes.size}\r\n")
                }
                append("Connection: close\r\n\r\n")
            }
        try {
            socket.getOutputStream().apply {
                write(headers.toByteArray(StandardCharsets.US_ASCII))
                if (chunked) {
                    writeChunked(bytes)
                } else {
                    write(bytes)
                }
                flush()
            }
        } catch (failure: java.io.IOException) {
            if (!chunked) throw failure
        }
    }

    private fun java.io.OutputStream.writeChunked(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val count = minOf(CHUNK_BYTES, bytes.size - offset)
            write(count.toString(16).toByteArray(StandardCharsets.US_ASCII))
            write(CRLF)
            write(bytes, offset, count)
            write(CRLF)
            offset += count
        }
        write("0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
    }

    private fun respondChunkedInitialize(
        socket: Socket,
        id: String?,
    ) {
        val headers =
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json\r\n" +
                "mcp-session-id: fixture-session\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Connection: close\r\n\r\n"
        val prefix =
            (
                """{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-03-26",""" +
                    """"capabilities":{"tools":{}},"serverInfo":{"name":"fixture","version":"1.0"},""" +
                    """"_padding":""""
            ).toByteArray(StandardCharsets.UTF_8)
        val paddingChunk = ByteArray(CHUNK_BYTES) { 'x'.code.toByte() }
        val suffix = "\"}}".toByteArray(StandardCharsets.UTF_8)
        try {
            socket.getOutputStream().apply {
                write(headers.toByteArray(StandardCharsets.US_ASCII))
                writeChunk(prefix)
                var remaining = extraInitializeFieldBytes
                while (remaining > 0) {
                    val count = minOf(remaining, paddingChunk.size)
                    writeChunk(paddingChunk, count)
                    remaining -= count
                }
                writeChunk(suffix)
                write("0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                flush()
            }
        } catch (_: java.io.IOException) {
            // Expected when the bounded client closes as soon as the sentinel byte arrives.
        }
    }

    private fun java.io.OutputStream.writeChunk(
        bytes: ByteArray,
        count: Int = bytes.size,
    ) {
        write(count.toString(16).toByteArray(StandardCharsets.US_ASCII))
        write(CRLF)
        write(bytes, 0, count)
        write(CRLF)
    }

    private fun respondEmpty(
        socket: Socket,
        status: Int,
    ) {
        val response = "HTTP/1.1 $status ${statusText(status)}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(response.toByteArray(StandardCharsets.US_ASCII))
            flush()
        }
    }

    private fun statusText(status: Int): String =
        when (status) {
            200 -> "OK"
            401 -> "Unauthorized"
            202 -> "Accepted"
            405 -> "Method Not Allowed"
            else -> "Not Found"
        }

    override fun close() {
        server.close()
        acceptor.join(1_000)
        clients.shutdownNow()
    }

    private data class Request(
        val method: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private companion object {
        const val MAX_HEADER_BYTES = 64 * 1024
        const val MAX_REQUEST_BYTES = 2 * 1024 * 1024
        const val CHUNK_BYTES = 8 * 1024
        val CRLF = byteArrayOf(13, 10)
        val METHOD_PATTERN = Regex("\\\"method\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
        val ID_PATTERN = Regex("\\\"id\\\"\\s*:\\s*([^,}]+)")
    }
}

/** Accepts TCP and immediately aborts the TLS handshake; no HTTP response or downgrade exists. */
private class TlsRejectingFixture : AutoCloseable {
    private val server = ServerSocket(0)
    private val connectionAccepted = CountDownLatch(1)
    private val acceptor =
        Thread(
            {
                while (!server.isClosed) {
                    try {
                        server.accept().use {
                            connectionAccepted.countDown()
                        }
                    } catch (_: java.net.SocketException) {
                        if (!server.isClosed) throw AssertionError("TLS fixture accept failed")
                    }
                }
            },
            "hxa070-tls-reject",
        ).apply {
            isDaemon = true
            start()
        }

    val endpoint = "https://127.0.0.1:${server.localPort}/mcp"

    fun awaitConnection(): Boolean = connectionAccepted.await(5, TimeUnit.SECONDS)

    override fun close() {
        server.close()
        acceptor.join(1_000)
    }
}
