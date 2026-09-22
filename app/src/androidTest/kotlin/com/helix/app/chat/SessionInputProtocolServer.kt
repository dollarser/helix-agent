package com.helix.app.chat

import com.helix.core.model.ProviderProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Owned loopback transport; retains only synthetic test payloads, never external-account traffic. */
internal class SessionInputProtocolServer(
    private val protocol: ProviderProtocol,
) : AutoCloseable {
    private data class Reply(
        val status: Int,
        val contentType: String,
        val body: String,
    )

    private val streamPath = if (protocol == ProviderProtocol.OPENAI_RESPONSES) "/v1/responses" else "/v1/messages"
    private val server = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
    private val activeSocket = AtomicReference<Socket?>()
    private val failure = AtomicReference<Throwable?>()
    private val bodies = Collections.synchronizedList(mutableListOf<String>())
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)

    @Volatile var exercise = false
    val port: Int get() = server.localPort
    val requestCount: Int get() = bodies.size
    private val thread = Thread(::acceptLoop, "helix-input-protocol-fixture").apply { isDaemon = true }

    fun start() = thread.start()

    fun request(index: Int): String = bodies[index]

    fun assertHealthy() {
        failure.get()?.let { throw AssertionError("Loopback protocol fixture failed", it) }
    }

    override fun close() {
        release.countDown()
        closeSockets()
        thread.join(2_000)
        check(!thread.isAlive) { "Loopback protocol fixture did not stop" }
    }

    private fun acceptLoop() {
        try {
            while (!server.isClosed) {
                server.accept().use { socket ->
                    activeSocket.set(socket)
                    socket.soTimeout = 30_000
                    handle(socket)
                    activeSocket.set(null)
                }
            }
        } catch (error: IOException) {
            if (!server.isClosed) failure.set(error)
        } catch (error: IllegalStateException) {
            failure.set(error)
        } finally {
            // A dead accept thread must never leave a listening socket that silently queues clients.
            closeSockets()
        }
    }

    private fun closeSockets() {
        try {
            server.close()
        } catch (error: IOException) {
            failure.compareAndSet(null, error)
        } finally {
            try {
                activeSocket.getAndSet(null)?.close()
            } catch (error: IOException) {
                failure.compareAndSet(null, error)
            }
        }
    }

    private fun handle(socket: Socket) {
        val input = DataInputStream(socket.getInputStream())
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            check(head.length < 32_768) { "Request header exceeds fixture bound" }
            head.append(input.readUnsignedByte().toChar())
        }
        val headers = head.toString().split("\r\n")
        val length =
            headers
                .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()
                ?.toInt() ?: 0
        require(length in 0..2_000_000)
        val bytes = ByteArray(length)
        input.readFully(bytes)
        val path = headers.first().split(' ')[1]
        val response = response(path, bytes.toString(Charsets.UTF_8))
        val body = response.body.toByteArray(Charsets.UTF_8)
        val reason = if (response.status == 200) "OK" else "Not Found"
        socket.getOutputStream().apply {
            write(
                (
                    "HTTP/1.1 ${response.status} $reason\r\nContent-Type: ${response.contentType}\r\n" +
                        "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                ).toByteArray(Charsets.UTF_8),
            )
            write(body)
            flush()
        }
    }

    private fun response(
        path: String,
        body: String,
    ): Reply =
        when (path) {
            "/v1/models" -> {
                Reply(
                    200,
                    "application/json",
                    """{"object":"list","data":[{"id":"fixture-model-a"}]}""",
                )
            }

            "/get_server_info" -> {
                Reply(200, "application/json", "{}")
            }

            streamPath -> {
                val stream = if (exercise) exerciseStream(body) else probeStream(body)
                Reply(200, "text/event-stream", stream)
            }

            else -> {
                Reply(404, "application/json", """{"error":"fixture route not found"}""")
            }
        }

    private fun exerciseStream(body: String): String {
        bodies.add(body)
        return if (bodies.size == 1) {
            entered.countDown()
            check(release.await(30, TimeUnit.SECONDS)) { "Steer did not release tool response" }
            SessionInputProtocolStreams.tool(protocol, probe = false)
        } else {
            SessionInputProtocolStreams.text(protocol)
        }
    }

    private fun probeStream(body: String): String {
        val tools = Json.parseToJsonElement(body).jsonObject["tools"]?.jsonArray
        return if (tools.isNullOrEmpty()) {
            SessionInputProtocolStreams.text(protocol)
        } else {
            SessionInputProtocolStreams.tool(protocol, probe = true)
        }
    }
}
