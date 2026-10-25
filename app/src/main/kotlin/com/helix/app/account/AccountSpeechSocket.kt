package com.helix.app.account

import com.helix.provider.api.wire.OkHttpSocketConnector
import com.helix.provider.api.wire.SocketConnection
import com.helix.provider.api.wire.SocketConnector
import com.helix.provider.api.wire.SocketHandshakeFailure
import com.helix.provider.api.wire.SocketMessage
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Bound to the creating login, including while the server is silent. Never replays an opened session. */
@Suppress("ThrowsCount") // Reject identity changes and refresh only a pre-open 401.
internal fun AccountSession.speechSocket(
    endpoint: String,
    delegate: SocketConnector = OkHttpSocketConnector(),
): SocketConnector {
    require(endpoint in setOf("wss://asr.sunlingzhang.com/ws/asr", "wss://tts.sunlingzhang.com/ws/tts"))
    val version = revision

    @Suppress("SwallowedException") // Only the safe HTTP category crosses into the socket adapter.
    fun credentials(failed: String? = null): AccountTokens? =
        try {
            runBlocking { authorization(failed, expectedRevision = version) }
        } catch (rejected: AccountRequestFailure) {
            throw SocketHandshakeFailure(rejected.status)
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            throw java.net.SocketTimeoutException("Speech authorization timeout")
        }
    return SocketConnector { url, headers ->
        require(url == endpoint && headers.isEmpty())
        var credential =
            credentials() ?: throw SocketHandshakeFailure(401)
        val identity = credential.identity

        fun connect(): SocketConnection =
            delegate.connect(
                url,
                mapOf(
                    "Authorization" to "Bearer ${credential.access}",
                    "X-Client-ID" to AccountSession.CLIENT_ID,
                    "User-Agent" to AccountSession.AGENT,
                ),
            )
        val socket =
            try {
                connect()
            } catch (rejected: SocketHandshakeFailure) {
                if (rejected.status != 401) throw rejected
                credential =
                    credentials(credential.access) ?: throw SocketHandshakeFailure(401)
                connect()
            }
        object : SocketConnection {
            private fun checkIdentity() {
                if (revision != version || this@speechSocket.identity != identity) {
                    socket.close()
                    throw IOException("Account changed")
                }
            }

            override fun sendText(value: String) {
                checkIdentity()
                socket.sendText(value)
                checkIdentity()
            }

            override fun sendBinary(value: ByteArray) {
                checkIdentity()
                socket.sendBinary(value)
                checkIdentity()
            }

            override fun receive(timeoutMillis: Long): SocketMessage? {
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
                do {
                    checkIdentity()
                    val next =
                        socket.receive(
                            minOf(
                                100,
                                maxOf(
                                    0,
                                    TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()),
                                ),
                            ),
                        )
                    checkIdentity()
                    if (next != null) return next
                } while (System.nanoTime() < deadline)
                return null
            }

            override fun close() = socket.close()
        }
    }
}
