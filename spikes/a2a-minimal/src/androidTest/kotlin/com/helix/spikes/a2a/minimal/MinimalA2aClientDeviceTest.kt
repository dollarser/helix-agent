package com.helix.spikes.a2a.minimal

import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MinimalA2aClientDeviceTest {
    @Test
    fun jsonRpcAndHttpJsonCarryAuthAndOneMiBPayload() {
        val requests = CopyOnWriteArrayList<CapturedRequest>()
        TestHttpServer(expectedRequests = 2) { socket, _ ->
            requests += socket.readRequest()
            socket.writeResponse(200, "application/json", "{\"ok\":true}")
        }.use { server ->
            val facade = OkHttpA2aClientFacade(OkHttpClient(), allowLoopbackHttp = true)
            val payload = "{\"message\":\"${"x".repeat(1024 * 1024)}\"}"
            val headers = mapOf("Authorization" to "Bearer device-fixture")

            assertEquals(200, facade.execute(server.request(A2aHttpBinding.JSON_RPC, payload, headers)).statusCode)
            assertEquals(200, facade.execute(server.request(A2aHttpBinding.HTTP_JSON, payload, headers)).statusCode)
            assertTrue(server.finished.await(10, TimeUnit.SECONDS))
        }

        assertEquals(2, requests.size)
        assertTrue(requests[0].contentType.startsWith("application/json"))
        assertTrue(requests[1].contentType.startsWith("application/a2a+json"))
        assertEquals("Bearer device-fixture", requests[0].headers["authorization"])
        assertTrue(requests.all { it.body.size > 1024 * 1024 })
    }

    @Test
    fun sseReconnectCarriesLastEventIdAndCancelSuppressesCallbacks() {
        val requests = CopyOnWriteArrayList<CapturedRequest>()
        val events = CopyOnWriteArrayList<A2aSseEvent>()
        val holdThird = CountDownLatch(1)
        TestHttpServer(expectedRequests = 3) { socket, index ->
            if (index == 2) {
                holdThird.await(5, TimeUnit.SECONDS)
            } else {
                requests += socket.readRequest()
                val eventId = index + 1
                socket.writeSse("id: $eventId\nevent: task\ndata: {\"sequence\":$eventId}\n\n")
            }
        }.use { server ->
            val facade = OkHttpA2aClientFacade(OkHttpClient(), allowLoopbackHttp = true)
            val firstClosed = CountDownLatch(1)
            facade.stream(server.request(A2aHttpBinding.JSON_RPC, "{}"), listener(events, firstClosed))
            assertTrue(firstClosed.await(5, TimeUnit.SECONDS))

            val secondClosed = CountDownLatch(1)
            facade.stream(
                server.request(A2aHttpBinding.JSON_RPC, "{}", mapOf("Last-Event-ID" to "1")),
                listener(events, secondClosed),
            )
            assertTrue(secondClosed.await(5, TimeUnit.SECONDS))

            val cancelledCallback = CountDownLatch(1)
            val handle =
                facade.stream(
                    server.request(A2aHttpBinding.JSON_RPC, "{}"),
                    listener(CopyOnWriteArrayList(), cancelledCallback),
                )
            assertTrue(server.acceptedThird.await(5, TimeUnit.SECONDS))
            handle.cancel()
            holdThird.countDown()
            assertFalse(cancelledCallback.await(300, TimeUnit.MILLISECONDS))
        }

        assertEquals(listOf("1", "2"), events.map { it.id })
        assertEquals(listOf(null, "1"), requests.take(2).map { it.headers["last-event-id"] })
    }

    @Test
    fun cleartextJsonAuthAndTlsFailuresStayExplicit() {
        val facade = OkHttpA2aClientFacade(OkHttpClient(), allowLoopbackHttp = true)
        assertThrows(IllegalArgumentException::class.java) {
            OkHttpA2aClientFacade(OkHttpClient()).execute(
                A2aHttpRequest("http://example.com", A2aHttpBinding.JSON_RPC, "{}"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            facade.execute(A2aHttpRequest("http://127.0.0.1:1", A2aHttpBinding.JSON_RPC, "not-json"))
        }

        TestHttpServer(expectedRequests = 1) { socket, _ ->
            socket.readRequest()
            socket.writeResponse(401, "application/json", "{\"error\":\"unauthorized\"}")
        }.use { server ->
            assertEquals(401, facade.execute(server.request(A2aHttpBinding.JSON_RPC, "{}")).statusCode)
        }

        TestHttpServer(expectedRequests = 1) { socket, _ ->
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray())
        }.use { server ->
            assertThrows(IOException::class.java) {
                facade.execute(
                    A2aHttpRequest("https://127.0.0.1:${server.port}/a2a", A2aHttpBinding.JSON_RPC, "{}"),
                )
            }
        }
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

            override fun onFailure(failure: IOException) {
                closed.countDown()
            }
        }

    private data class CapturedRequest(
        val headers: Map<String, String>,
        val body: ByteArray,
    ) {
        val contentType: String get() = headers.getValue("content-type")
    }

    private class TestHttpServer(
        expectedRequests: Int,
        private val handler: (Socket, Int) -> Unit,
    ) : Closeable {
        private val server = ServerSocket(0, expectedRequests)
        val port: Int = server.localPort
        val finished = CountDownLatch(1)
        val acceptedThird = CountDownLatch(1)
        private val thread =
            Thread {
                try {
                    repeat(expectedRequests) { index ->
                        server.accept().use { socket ->
                            if (index == 2) acceptedThird.countDown()
                            handler(socket, index)
                        }
                    }
                } finally {
                    finished.countDown()
                }
            }.apply {
                isDaemon = true
                start()
            }

        fun request(
            binding: A2aHttpBinding,
            payload: String,
            headers: Map<String, String> = emptyMap(),
        ): A2aHttpRequest = A2aHttpRequest("http://127.0.0.1:$port/a2a", binding, payload, headers)

        override fun close() {
            server.close()
            thread.join(1_000)
        }
    }

    private fun Socket.readRequest(): CapturedRequest {
        val input = BufferedInputStream(getInputStream())
        val headerBytes = mutableListOf<Byte>()
        var tail = 0
        while (tail != HEADER_END) {
            val next = input.read()
            if (next < 0) throw IOException("HTTP request headers ended early")
            headerBytes += next.toByte()
            tail = ((tail shl 8) or next) and HEADER_END_MASK
        }
        val lines =
            headerBytes
                .toByteArray()
                .toString(StandardCharsets.ISO_8859_1)
                .trim()
                .lines()
        val headers =
            lines.drop(1).associate { line ->
                val separator = line.indexOf(':')
                line.substring(0, separator).lowercase(Locale.US) to line.substring(separator + 1).trim()
            }
        val size = headers.getValue("content-length").toInt()
        val body = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = input.read(body, offset, size - offset)
            if (count < 0) throw IOException("HTTP request body ended early")
            offset += count
        }
        return CapturedRequest(headers, body)
    }

    private fun Socket.writeResponse(
        status: Int,
        contentType: String,
        body: String,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        BufferedOutputStream(getOutputStream()).use { output ->
            output.write(
                "HTTP/1.1 $status Result\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\n\r\n"
                    .toByteArray(StandardCharsets.ISO_8859_1),
            )
            output.write(bytes)
        }
    }

    private fun Socket.writeSse(body: String) = writeResponse(200, "text/event-stream", body)

    private companion object {
        const val HEADER_END = 0x0d0a0d0a
        const val HEADER_END_MASK = 0xffffffff.toInt()
    }
}
