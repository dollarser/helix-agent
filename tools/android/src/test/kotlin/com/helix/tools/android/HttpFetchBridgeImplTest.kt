package com.helix.tools.android

import com.helix.core.model.SafetyProfile
import com.helix.core.policy.NetworkOriginScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.KeyStore
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

/** Real host sockets, pinned loopback DNS, and a test-only trusted localhost certificate. */
class HttpFetchBridgeImplTest {
    @Test
    fun trustedCorrectHostIsFetchedOverPinnedTls() =
        withTestTrust { context ->
            LocalServer(context).use { server ->
                val outcome = bridge(policy(server.port)).fetch(request(server.url("localhost", true)))
                assertEquals(HttpFetchStatus.FETCHED, outcome.status)
                assertEquals("ok", outcome.body)
                assertTrue(server.request().contains("Host: localhost:${server.port}\r\n"))
            }
        }

    @Test
    fun trustedWrongHostFailsBeforeAnyHttpBytes() =
        withTestTrust { context ->
            LocalServer(context).use { server ->
                val outcome = bridge(policy(server.port, "wrong.local")).fetch(request(server.url("wrong.local", true)))
                assertEquals(HttpFetchStatus.ERROR, outcome.status)
                assertTrue(server.accepted.get())
                assertEquals("", server.request())
            }
        }

    @Test
    fun allowedRedirectUsesFreshPinnedConnection() {
        LocalServer().use { target ->
            LocalServer(response = { redirect(target.url()) }).use { source ->
                val policy = policy(source.port, targetPort = target.port)
                val outcome = bridge(policy).fetch(request(source.url()))
                assertEquals(HttpFetchStatus.FETCHED, outcome.status)
                assertEquals(1, outcome.redirectCount)
                assertTrue(source.request().startsWith("GET / HTTP/1.1"))
                assertTrue(target.request().startsWith("GET / HTTP/1.1"))
            }
        }
    }

    @Test
    fun scopeRevocationDuringRedirectPreventsTargetConnection() {
        redirectPolicyChange(expand = false, standard = false)
    }

    @Test
    fun profileRevocationDuringRedirectPreventsTargetConnection() {
        redirectPolicyChange(expand = false, standard = true)
    }

    @Test
    fun addedScopeDuringRedirectDoesNotExpandOriginalAuthorization() {
        redirectPolicyChange(expand = true, standard = false)
    }

    @Test
    fun mutatingOriginalScopeSetCannotExpandAuthorization() {
        LocalServer().use { target ->
            val scopes = mutableSetOf<NetworkOriginScope>()
            LocalServer(response = {
                scopes += NetworkOriginScope("localhost", target.port)
                redirect(target.url())
            }).use { source ->
                scopes += NetworkOriginScope("localhost", source.port)
                val outcome = bridge(EgressPolicy(SafetyProfile.ADVANCED, scopes)).fetch(request(source.url()))
                assertEquals(HttpFetchStatus.REFUSED, outcome.status)
                target.assertNoConnection()
            }
        }
    }

    @Test
    fun revocationDuringDnsResolutionPreventsFirstConnection() {
        LocalServer().use { server ->
            val live = AtomicReference(policy(server.port))
            val bridge =
                HttpFetchBridgeImpl(
                    provider(live),
                    object : AddressResolver {
                        override fun resolve(host: String): List<ByteArray> {
                            live.set(EgressPolicy(SafetyProfile.STANDARD, emptySet()))
                            return listOf(LOOPBACK)
                        }
                    },
                )
            assertEquals(HttpFetchStatus.REFUSED, bridge.fetch(request(server.url())).status)
            server.assertNoConnection()
        }
    }

    @Test
    fun profileExpansionDuringResolutionDoesNotAuthorizeOriginalStandardCall() {
        LocalServer().use { server ->
            val live = AtomicReference(EgressPolicy(SafetyProfile.STANDARD, emptySet()))
            val addresses =
                object : AddressResolver {
                    override fun resolve(host: String): List<ByteArray> {
                        live.set(policy(server.port))
                        return listOf(LOOPBACK)
                    }
                }
            val outcome = HttpFetchBridgeImpl(provider(live), addresses).fetch(request(server.url()))
            assertEquals(HttpFetchStatus.REFUSED, outcome.status)
            assertEquals("lan-not-allowed", outcome.reason)
            server.assertNoConnection()
        }
    }

    @Test
    fun mixedResolvedSetIsRefusedWithoutConnectingToAllowedSubset() {
        LocalServer().use { server ->
            val addresses =
                object : AddressResolver {
                    override fun resolve(host: String) =
                        listOf(LOOPBACK, byteArrayOf(169.toByte(), 254.toByte(), 169.toByte(), 254.toByte()))
                }
            val live = AtomicReference(policy(server.port))
            val outcome = HttpFetchBridgeImpl(provider(live), addresses).fetch(request(server.url()))
            assertEquals(HttpFetchStatus.REFUSED, outcome.status)
            assertEquals("metadata-blocked", outcome.reason)
            server.assertNoConnection()
        }
    }

    @Test
    fun revocationDuringTlsHandshakePreventsHttpBytes() =
        withTestTrust { context ->
            val live = AtomicReference(EgressPolicy(SafetyProfile.ADVANCED, emptySet()))
            LocalServer(context, beforeHandshake = {
                live.set(EgressPolicy(SafetyProfile.STANDARD, emptySet()))
            }).use { server ->
                live.set(policy(server.port))
                val outcome = HttpFetchBridgeImpl(provider(live), resolver).fetch(request(server.url(tls = true)))
                assertEquals(HttpFetchStatus.REFUSED, outcome.status)
                assertEquals("", server.request())
            }
        }

    @Test
    fun tlsHandshakeHonorsFetchDeadline() {
        // A plain listener accepts TLS but deliberately never responds to ClientHello.
        ServerSocket(0, 1, InetAddress.getByAddress(LOOPBACK)).use { listener ->
            val accepted = CompletableFuture<Socket>()
            val thread =
                Thread { accepted.complete(listener.accept()) }.apply {
                    isDaemon = true
                    start()
                }
            val outcome =
                bridge(policy(listener.localPort)).fetch(
                    request("https://localhost:${listener.localPort}/").copy(
                        deadlineMillis = System.currentTimeMillis() + 500,
                    ),
                )
            accepted.get(3, TimeUnit.SECONDS).close()
            thread.join(3_000)
            assertEquals(HttpFetchStatus.TIMEOUT, outcome.status)
        }
    }

    private fun redirectPolicyChange(
        expand: Boolean,
        standard: Boolean,
    ) {
        LocalServer().use { target ->
            val live = AtomicReference(EgressPolicy(SafetyProfile.ADVANCED, emptySet()))
            LocalServer(response = {
                live.set(
                    if (standard) {
                        EgressPolicy(SafetyProfile.STANDARD, emptySet())
                    } else if (expand) {
                        policy(target.port)
                    } else {
                        EgressPolicy(SafetyProfile.ADVANCED, emptySet())
                    },
                )
                redirect(target.url())
            }).use { source ->
                live.set(policy(source.port, targetPort = if (expand) null else target.port))
                val outcome = HttpFetchBridgeImpl(provider(live), resolver).fetch(request(source.url()))
                assertEquals(HttpFetchStatus.REFUSED, outcome.status)
                assertEquals(if (standard) "lan-not-allowed" else "scope-violation", outcome.reason)
                assertTrue(source.request().isNotEmpty())
                target.assertNoConnection()
            }
        }
    }

    private fun bridge(policy: EgressPolicy) =
        HttpFetchBridgeImpl(
            object : EgressPolicyProvider {
                override fun current() = policy
            },
            resolver,
        )

    private fun provider(live: AtomicReference<EgressPolicy>) =
        object : EgressPolicyProvider {
            override fun current(): EgressPolicy = live.get()
        }

    private fun policy(
        port: Int,
        host: String = "localhost",
        targetPort: Int? = null,
    ) = EgressPolicy(
        SafetyProfile.ADVANCED,
        buildSet {
            add(NetworkOriginScope(host, port))
            targetPort?.let { add(NetworkOriginScope(host, it)) }
        },
    )

    private fun request(url: String) = HttpFetchRequest(url, "GET", 1024, System.currentTimeMillis() + 5_000)

    private fun redirect(url: String) = "HTTP/1.1 302 Found\r\nLocation: $url\r\nContent-Length: 0\r\n\r\n"

    // Restore process-global default in finally; no trust-all manager or hostname-verifier bypass.
    private fun withTestTrust(test: (SSLContext) -> Unit) {
        val store = KeyStore.getInstance("PKCS12")
        requireNotNull(javaClass.getResourceAsStream("/http-fetch/localhost.p12")).use {
            store.load(it, "test-only".toCharArray())
        }
        val keys =
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(store, "test-only".toCharArray())
            }
        val trustStore =
            KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("localhost", store.getCertificate("server"))
            }
        val trust =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(trustStore)
            }
        val context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, trust.trustManagers, null) }
        val previous = SSLContext.getDefault()
        try {
            SSLContext.setDefault(context)
            test(context)
        } finally {
            SSLContext.setDefault(previous)
        }
    }

    private class LocalServer(
        context: SSLContext? = null,
        beforeHandshake: () -> Unit = {},
        response: () -> String = { "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok" },
    ) : AutoCloseable {
        private val listener =
            context?.serverSocketFactory?.createServerSocket(0, 1, InetAddress.getByAddress(LOOPBACK))
                ?: ServerSocket(0, 1, InetAddress.getByAddress(LOOPBACK))
        val port = listener.localPort
        val accepted = AtomicBoolean(false)
        private val received = CompletableFuture<String>()
        private val worker =
            Thread {
                try {
                    listener.accept().use { socket ->
                        accepted.set(true)
                        socket.soTimeout = 3_000
                        if (socket is SSLSocket) {
                            beforeHandshake()
                            try {
                                socket.startHandshake()
                            } catch (_: java.io.IOException) {
                                // Peer rejection can surface as a fatal alert or a broken pipe.
                                received.complete("")
                                return@use
                            }
                        }
                        val input = socket.getInputStream()
                        val bytes = StringBuilder()
                        while (!bytes.endsWith("\r\n\r\n")) {
                            val next = input.read()
                            if (next < 0) break
                            bytes.append(next.toChar())
                            check(bytes.length < 8192)
                        }
                        if (bytes.isNotEmpty()) {
                            socket.getOutputStream().write(response().toByteArray(Charsets.US_ASCII))
                            socket.getOutputStream().flush()
                        }
                        received.complete(bytes.toString())
                    }
                } catch (error: SocketException) {
                    if (listener.isClosed) received.complete("") else received.completeExceptionally(error)
                } catch (error: Exception) {
                    received.completeExceptionally(error)
                }
            }.apply {
                isDaemon = true
                start()
            }

        fun url(
            host: String = "localhost",
            tls: Boolean = false,
        ) = "${if (tls) "https" else "http"}://$host:$port/"

        fun request(): String = received.get(5, TimeUnit.SECONDS)

        fun assertNoConnection() {
            close()
            assertFalse(accepted.get())
            assertEquals("", request())
        }

        override fun close() {
            listener.close()
            worker.join(5_000)
            check(!worker.isAlive) { "local server did not exit" }
        }
    }

    private companion object {
        val LOOPBACK = byteArrayOf(127, 0, 0, 1)
        val resolver =
            object : AddressResolver {
                override fun resolve(host: String) = listOf(LOOPBACK)
            }
    }
}
