package com.helix.app.voice

import com.helix.provider.api.wire.OkHttpSocketConnector
import com.helix.provider.api.wire.SocketHandshakeFailure
import com.helix.provider.api.wire.SocketMessage
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SocketWireTest {
    @Test fun transportPreservesBusyAndServiceFailureCloseCodes() {
        for (code in listOf(1013, 1011)) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(
                    MockResponse
                        .Builder()
                        .webSocketUpgrade(
                            object : WebSocketListener() {
                                override fun onOpen(
                                    webSocket: WebSocket,
                                    response: Response,
                                ) {
                                    webSocket.close(code, null)
                                }
                            },
                        ).build(),
                )
                OkHttpSocketConnector().connect(server.url("/socket").toString(), emptyMap()).use { stream ->
                    assertEquals(code, (stream.receive(3000) as SocketMessage.Closed).code)
                }
            }
        }
    }

    @Test fun oversizedFirstMessageFailsClosedWithoutEscapingListener() {
        MockWebServer().use { server ->
            server.start()
            val connected = CountDownLatch(1)
            val sent = CountDownLatch(1)
            val queued = AtomicBoolean(false)
            server.enqueue(
                MockResponse
                    .Builder()
                    .webSocketUpgrade(
                        object : WebSocketListener() {
                            override fun onOpen(
                                webSocket: WebSocket,
                                response: Response,
                            ) {
                                if (connected.await(3, TimeUnit.SECONDS)) {
                                    queued.set(webSocket.send("x".repeat(65537)))
                                }
                                sent.countDown()
                            }
                        },
                    ).build(),
            )
            OkHttpSocketConnector().connect(server.url("/socket").toString(), emptyMap()).use { stream ->
                connected.countDown()
                assertTrue(sent.await(3, TimeUnit.SECONDS))
                assertTrue(queued.get())
                assertThrows(IOException::class.java) { stream.receive(3000) }
            }
        }
    }

    @Test fun handshakeRejectionIsTypedAndCancelUnblocksSilentPeer() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(403).build())
            assertEquals(
                403,
                assertThrows(SocketHandshakeFailure::class.java) {
                    OkHttpSocketConnector().connect(server.url("/socket").toString(), emptyMap())
                }.status,
            )
            server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {}).build())
            val stream = OkHttpSocketConnector().connect(server.url("/socket").toString(), emptyMap())
            val worker = Executors.newSingleThreadExecutor()
            try {
                val waiting =
                    worker.submit<Boolean> {
                        try {
                            stream.receive(30000)
                            false
                        } catch (_: IOException) {
                            true
                        }
                    }
                stream.close()
                assertTrue(waiting.get(2, TimeUnit.SECONDS))
            } finally {
                stream.close()
                worker.shutdownNow()
            }
        }
    }
}
