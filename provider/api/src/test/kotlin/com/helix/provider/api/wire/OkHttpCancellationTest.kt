package com.helix.provider.api.wire

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class OkHttpCancellationTest {
    @Test fun cancellationClosesSocketWhileWaitingForHeaders() = verifyCancellation(sendBody = false)

    @Test fun cancellationClosesSocketWhileReadingBody() = verifyCancellation(sendBody = true)

    private fun verifyCancellation(sendBody: Boolean) =
        runBlocking {
            val accepted = CompletableDeferred<Unit>()
            val receivedChunk = CompletableDeferred<Unit>()
            val disconnected = CompletableDeferred<Boolean>()
            val active = AtomicReference<Socket?>()
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                val peer =
                    thread(isDaemon = true) {
                        server.accept().use { socket ->
                            active.set(socket)
                            socket.soTimeout = 10000
                            val input = socket.getInputStream()
                            var head = ""
                            while (!head.endsWith("\r\n\r\n")) head += input.read().also { check(it >= 0) }.toChar()
                            if (sendBody) {
                                socket.getOutputStream().apply {
                                    write("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\npartial".toByteArray())
                                    flush()
                                }
                            }
                            accepted.complete(Unit)
                            disconnected.complete(input.read() == -1)
                        }
                    }
                val job =
                    launch(Dispatchers.IO) {
                        val response =
                            OkHttpWireClient().open(
                                WireRequest("GET", "http://127.0.0.1:${server.localPort}/held", emptyMap(), null),
                            )
                        try {
                            response.body.forEachChunk {
                                receivedChunk.complete(Unit)
                                true
                            }
                        } finally {
                            response.body.close()
                        }
                    }
                try {
                    withTimeout(3000) {
                        accepted.await()
                        if (sendBody) receivedChunk.await()
                    }
                    withTimeout(2000) { job.cancelAndJoin() }
                    assertTrue(withTimeout(2000) { disconnected.await() })
                } finally {
                    active.get()?.close()
                    job.cancelAndJoin()
                    peer.join(1000)
                }
            }
        }
}
