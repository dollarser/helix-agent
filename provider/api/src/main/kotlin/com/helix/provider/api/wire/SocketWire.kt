package com.helix.provider.api.wire

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded, blocking duplex port. Call on an interruptible IO worker; close unblocks receive. */
public sealed interface SocketMessage {
    public class Text(
        public val value: String,
    ) : SocketMessage

    public class Binary(
        public val value: ByteArray,
    ) : SocketMessage

    public class Closed(
        public val code: Int,
    ) : SocketMessage
}

public interface SocketConnection : AutoCloseable {
    public fun sendText(value: String)

    public fun sendBinary(value: ByteArray)

    public fun receive(timeoutMillis: Long): SocketMessage?
}

public fun interface SocketConnector {
    public fun connect(
        url: String,
        headers: Map<String, String>,
    ): SocketConnection
}

public class SocketHandshakeFailure(
    public val status: Int,
) : IOException("Socket handshake rejected: $status")

/** No redirects or automatic application-message replay. Queue capacity bounds received audio. */
public class OkHttpSocketConnector : SocketConnector {
    private val client =
        OkHttpClient
            .Builder()
            .dns {
                com.helix.core.policy.network.NativeNetwork
                    .resolve(it)
            }.followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

    @Suppress("TooGenericExceptionCaught") // Every failed handshake releases its allocated socket.
    override fun connect(
        url: String,
        headers: Map<String, String>,
    ): SocketConnection {
        val stream = Stream()
        val request =
            Request
                .Builder()
                .url(url)
                .apply {
                    headers.forEach { (key, value) ->
                        header(key, value)
                    }
                }.build()
        stream.socket = client.newWebSocket(request, stream)
        try {
            if (!stream.open.await(30, TimeUnit.SECONDS)) throw IOException("Socket handshake timeout")
            stream.failure?.let { throw it }
            return stream
        } catch (error: Exception) {
            stream.close()
            throw error
        }
    }

    @Suppress("TooManyFunctions") // WebSocketListener and the duplex port share one connection owner.
    private class Stream :
        WebSocketListener(),
        SocketConnection {
        lateinit var socket: WebSocket
        val open = CountDownLatch(1)

        @Volatile var failure: IOException? = null
        private val closed = AtomicBoolean()
        private val incoming = ArrayBlockingQueue<SocketMessage>(1)

        @Volatile private var terminal: SocketMessage.Closed? = null

        override fun onOpen(
            webSocket: WebSocket,
            response: Response,
        ) {
            open.countDown()
        }

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?,
        ) {
            failure = response?.let { SocketHandshakeFailure(it.code) } ?: IOException("Socket transport failed")
            open.countDown()
            terminal = SocketMessage.Closed(1006)
        }

        override fun onClosing(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            terminal = SocketMessage.Closed(code)
            webSocket.close(code, null)
        }

        override fun onClosed(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            terminal = SocketMessage.Closed(code)
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            if (text.length > 65536) {
                closed.set(true)
                incoming.clear()
                webSocket.cancel()
                return
            }
            enqueue(SocketMessage.Text(text))
        }

        override fun onMessage(
            webSocket: WebSocket,
            bytes: ByteString,
        ) {
            if (bytes.size > 5_760_000) {
                closed.set(true)
                incoming.clear()
                webSocket.cancel()
                return
            }
            enqueue(SocketMessage.Binary(bytes.toByteArray()))
        }

        private fun enqueue(message: SocketMessage) {
            while (!closed.get()) {
                if (incoming.offer(message, 100, TimeUnit.MILLISECONDS)) return
            }
        }

        override fun sendText(value: String) {
            check(!closed.get() && socket.queueSize() <= 262144 && socket.send(value)) { "Socket send failed" }
        }

        override fun sendBinary(value: ByteArray) {
            require(value.size <= 320000)
            check(
                !closed.get() && socket.queueSize() <= 262144 && socket.send(value.toByteString()),
            ) { "Socket send failed" }
        }

        @Suppress("ReturnCount") // Queue, terminal and timeout are independent outcomes.
        override fun receive(timeoutMillis: Long): SocketMessage? {
            require(timeoutMillis >= 0)
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            do {
                if (closed.get()) throw IOException("Socket cancelled")
                incoming.poll()?.let { return it }
                terminal?.let { return it }
                failure?.let { throw it }
                if (timeoutMillis == 0L) return null
                incoming
                    .poll(
                        minOf(100, maxOf(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))),
                        TimeUnit.MILLISECONDS,
                    )?.let {
                        return it
                    }
            } while (System.nanoTime() < deadline)
            return null
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                incoming.clear()
                socket.cancel()
            }
        }
    }
}
