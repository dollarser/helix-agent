package com.helix.app.account

import com.helix.provider.api.wire.SocketConnection
import com.helix.provider.api.wire.SocketConnector
import com.helix.provider.api.wire.SocketHandshakeFailure
import com.helix.provider.api.wire.SocketMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AccountSpeechSocketTest {
    private val endpoint = "wss://tts.sunlingzhang.com/ws/tts"

    private class Fake : SocketConnection {
        var closed = false
        var receive: () -> SocketMessage? = { null }

        override fun sendText(value: String) = Unit

        override fun sendBinary(value: ByteArray) = Unit

        override fun receive(timeoutMillis: Long) = receive()

        override fun close() {
            closed = true
        }
    }

    @Test fun refreshGatewayFailureRetainsServerStatusAndNeverOpensSocket() {
        var connections = 0
        val account =
            AccountSession(
                MemoryAccountTokens(AccountTokens("expired", "refresh", 1, 2000000, "session")),
                object : com.helix.provider.api.wire.WireClient {
                    override suspend fun open(request: com.helix.provider.api.wire.WireRequest) = accountResponse(530)
                },
                { 100L },
            )
        val failure =
            assertThrows(SocketHandshakeFailure::class.java) {
                account
                    .speechSocket(
                        endpoint,
                        SocketConnector { _, _ ->
                            connections++
                            Fake()
                        },
                    ).connect(endpoint, emptyMap())
            }
        assertEquals(530, failure.status)
        assertEquals(0, connections)
    }

    @Test fun authorizationDeadlineIsTransportFailureAndNeverOpensSocket() {
        val account =
            AccountSession(
                MemoryAccountTokens(AccountTokens("expired", "refresh", 1, 2000000, "session")),
                object : com.helix.provider.api.wire.WireClient {
                    override suspend fun open(
                        request: com.helix.provider.api.wire.WireRequest,
                    ): com.helix.provider.api.wire.WireResponse =
                        kotlinx.coroutines.withTimeout(1) { kotlinx.coroutines.awaitCancellation() }
                },
                { 100L },
            )
        assertThrows(java.net.SocketTimeoutException::class.java) {
            account
                .speechSocket(
                    endpoint,
                    SocketConnector {
                        _,
                        _,
                        ->
                        error("No connection")
                    },
                ).connect(endpoint, emptyMap())
        }
        assertTrue(!account.signedIn.value)
    }

    @Test fun loginAndClientIdentityAreBothRequiredAndTargetsPinned() {
        val account = testAccount()
        var calls = 0
        val connector =
            account.speechSocket(
                endpoint,
                SocketConnector { url, headers ->
                    calls++
                    assertEquals(endpoint, url)
                    assertEquals("Bearer fixture-access", headers["Authorization"])
                    assertEquals(AccountSession.CLIENT_ID, headers["X-Client-ID"])
                    Fake()
                },
            )
        connector.connect(endpoint, emptyMap()).close()
        assertThrows(IllegalArgumentException::class.java) {
            connector.connect(
                endpoint,
                mapOf("Authorization" to "override"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) { account.speechSocket("wss://evil.test/ws/tts") }
        assertThrows(IOException::class.java) {
            AccountSession(MemoryAccountTokens())
                .speechSocket(
                    endpoint,
                    SocketConnector {
                        _,
                        _,
                        ->
                        error("Must not connect")
                    },
                ).connect(endpoint, emptyMap())
        }
        assertEquals(1, calls)
    }

    @Test fun onlyPreOpen401RefreshesOnceAnd403DoesNotRetry() {
        for (status in listOf(401, 403)) {
            var calls = 0
            val connector =
                testAccount().speechSocket(
                    endpoint,
                    SocketConnector { _, headers ->
                        calls++
                        if (calls == 1) throw SocketHandshakeFailure(status)
                        assertEquals("Bearer fixture-renewed", headers["Authorization"])
                        Fake()
                    },
                )
            if (status == 401) {
                connector.connect(endpoint, emptyMap()).close()
                assertEquals(2, calls)
            } else {
                assertThrows(SocketHandshakeFailure::class.java) { connector.connect(endpoint, emptyMap()) }
                assertEquals(1, calls)
            }
        }
    }

    @Test fun logoutWhileReceivingDropsLateAudioAndClosesConnection() {
        val account = testAccount()
        val wire = Fake()
        val stream = account.speechSocket(endpoint, SocketConnector { _, _ -> wire }).connect(endpoint, emptyMap())
        wire.receive = {
            runBlocking { account.logout() }
            SocketMessage.Binary(byteArrayOf(1, 2))
        }
        assertThrows(IOException::class.java) { stream.receive(1000) }
        assertTrue(wire.closed)
    }

    @Test fun switchingLoginInvalidatesOpenAndNotYetStartedConnections() {
        val account = testAccount()
        val wire = Fake()
        val pending = account.speechSocket(endpoint, SocketConnector { _, _ -> error("Old login cannot connect") })
        val stream = account.speechSocket(endpoint, SocketConnector { _, _ -> wire }).connect(endpoint, emptyMap())
        runBlocking { account.login("synthetic@example.test", "synthetic-password") }
        assertThrows(IOException::class.java) { stream.sendText("text") }
        assertThrows(IOException::class.java) { pending.connect(endpoint, emptyMap()) }
        assertTrue(wire.closed)
    }
}
