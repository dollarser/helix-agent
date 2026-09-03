package com.helix.tools.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.core.model.SafetyProfile
import com.helix.core.policy.NetworkOriginScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * The on-device verification gate for HXA-066 (verification-matrix row
 * `:tools:android:connectedDebugAndroidTest`) — the real [HttpFetchBridgeImpl] transport against a
 * REAL local [ServerSocket] HTTP server on loopback, plus the pure-JVM [com.helix.core.policy.SsrfAddressPolicy]
 * decisions it enforces (the security doc 7.9 "DNS rebinding / redirect / peer / scope" cases).
 *
 * What the device proves here:
 * - a scoped loopback host under ADVANCED FETCHES through the real socket (connect → peer revalidate
 *   → HTTP body), and the same host is REFUSED under STANDARD (`lan-not-allowed`) and under ADVANCED
 *   without a matching exact scope (`scope-violation`);
 * - a public hostname that REBINDS to loopback or to the cloud-metadata address is REFUSED
 *   (`rebind-blocked` / `metadata-blocked`) with no connection ever attempted — the fake
 *   [AddressResolver] makes the rebind deterministic;
 * - a `302` redirect is followed AND the new origin re-runs the whole resolve → check → connect
 *   decision (`redirectCount` advances), while an endless loop hits the hop cap (`redirect-limit`);
 * - `HEAD` returns the status with an empty body, an oversized body is cut at the byte cap
 *   (`truncated`), and a fetch whose deadline is already past is a stable `TIMEOUT`.
 *
 * The loopback transport is a raw [java.net.Socket] (not the platform HTTP client), so it is not
 * subject to the cleartext-traffic policy and connects to 127.0.0.1 by construction. The TLS
 * handshake path (SNI/cert against the ORIGINAL hostname) is exercised by the same
 * [com.helix.tools.android.HttpFetchBridgeImpl.tlsIfNecessary] code but is not asserted here — a
 * device test cannot present a trusted certificate to loopback, so it is covered by the transport's
 * construction, not a green light.
 */
@RunWith(AndroidJUnit4::class)
@Suppress("SwallowedException", "TooGenericExceptionCaught") // throwaway test server
class HttpFetchBridgeDeviceTest {
    private lateinit var server: LocalServer

    @Before
    fun setUp() {
        server = startLocalServer()
    }

    @After
    fun stopLocalServer() {
        server.close()
    }

    // ── scoped loopback under ADVANCED vs the Standard/Advanced boundaries ──────────────

    @Test
    fun scopedLoopbackFetchesUnderAdvanced() {
        val bridge = HttpFetchBridgeImpl(advancedScopeFor(server.port), RealAddressResolver())
        val out = bridge.fetch(fetch("http://127.0.0.1:${server.port}/hello"))
        assertEquals(HttpFetchStatus.FETCHED, out.status)
        assertEquals("http://127.0.0.1:${server.port}/hello", out.finalUrl)
        assertEquals(200, out.httpStatus)
        assertEquals("text/plain", out.contentType)
        assertEquals("hello world", out.body)
        assertEquals(11L, out.bodyBytes)
        assertEquals(false, out.truncated)
        assertEquals(0, out.redirectCount)
    }

    @Test
    fun loopbackIsRefusedUnderStandard() {
        val bridge = HttpFetchBridgeImpl(policy(SafetyProfile.STANDARD), RealAddressResolver())
        val out = bridge.fetch(fetch("http://127.0.0.1:${server.port}/hello"))
        assertEquals(HttpFetchStatus.REFUSED, out.status)
        assertEquals("lan-not-allowed", out.reason)
        assertEquals("", out.body)
    }

    @Test
    fun loopbackUnderAdvancedWithoutAScopeIsRefused() {
        val bridge = HttpFetchBridgeImpl(policy(SafetyProfile.ADVANCED), RealAddressResolver())
        val out = bridge.fetch(fetch("http://127.0.0.1:${server.port}/hello"))
        assertEquals(HttpFetchStatus.REFUSED, out.status)
        assertEquals("scope-violation", out.reason)
    }

    @Test
    fun loopbackWithAMismatchedScopeIsRefused() {
        val wrongPortScope = setOf(NetworkOriginScope("127.0.0.1", 8080))
        val bridge =
            HttpFetchBridgeImpl(
                object : EgressPolicyProvider {
                    override fun current(): EgressPolicy = EgressPolicy(SafetyProfile.ADVANCED, wrongPortScope)
                },
                RealAddressResolver(),
            )
        val out = bridge.fetch(fetch("http://127.0.0.1:${server.port}/hello"))
        assertEquals(HttpFetchStatus.REFUSED, out.status)
        assertEquals("scope-violation", out.reason)
    }

    // ── DNS-rebinding / metadata: a public name resolving to a non-public address ──────

    @Test
    fun publicHostnameRebindingToLoopbackIsRefused() {
        val bridge = HttpFetchBridgeImpl(policy(SafetyProfile.STANDARD), resolverReturns(loopbackV4()))
        val out = bridge.fetch(fetch("http://public.example/hello"))
        assertEquals(HttpFetchStatus.REFUSED, out.status)
        assertEquals("rebind-blocked", out.reason)
    }

    @Test
    fun publicHostnameRebindingToMetadataIsRefused() {
        val bridge = HttpFetchBridgeImpl(policy(SafetyProfile.STANDARD), resolverReturns(metadataV4()))
        val out = bridge.fetch(fetch("http://metadata.example/"))
        assertEquals(HttpFetchStatus.REFUSED, out.status)
        assertEquals("metadata-blocked", out.reason)
    }

    // ── redirect: follow + re-check the new origin; cap the hops ───────────────────────

    @Test
    fun redirectIsFollowedAndTheNewOriginIsRechecked() {
        val bridge = HttpFetchBridgeImpl(advancedScopeFor(server.port), RealAddressResolver())
        val out = bridge.fetch(fetch("http://127.0.0.1:${server.port}/redirect"))
        assertEquals(HttpFetchStatus.FETCHED, out.status)
        assertEquals("http://127.0.0.1:${server.port}/hello", out.finalUrl)
        assertEquals("hello world", out.body)
        assertEquals(1, out.redirectCount)
    }

    @Test
    fun anEndlessRedirectLoopHitsTheHopCap() {
        val bridge = HttpFetchBridgeImpl(advancedScopeFor(server.port), RealAddressResolver())
        val out = bridge.fetch(fetch("http://127.0.0.1:${server.port}/redirect-loop", maxRedirects = 2))
        assertEquals(HttpFetchStatus.REFUSED, out.status)
        assertEquals("redirect-limit", out.reason)
    }

    // ── HEAD / truncation / timeout ─────────────────────────────────────────────────────

    @Test
    fun headReturnsTheStatusWithAnEmptyBody() {
        val bridge = HttpFetchBridgeImpl(advancedScopeFor(server.port), RealAddressResolver())
        val out =
            bridge.fetch(
                HttpFetchRequest("http://127.0.0.1:${server.port}/hello", "HEAD", FETCH_BODY_BYTES, futureDeadline()),
            )
        assertEquals(HttpFetchStatus.FETCHED, out.status)
        assertEquals(200, out.httpStatus)
        assertEquals("", out.body)
        assertEquals(0L, out.bodyBytes)
    }

    @Test
    fun anOversizedBodyIsCutAtTheByteCap() {
        val bridge = HttpFetchBridgeImpl(advancedScopeFor(server.port), RealAddressResolver())
        val out =
            bridge.fetch(
                HttpFetchRequest("http://127.0.0.1:${server.port}/big", "GET", TRUNCATE_CAP, futureDeadline()),
            )
        assertEquals(HttpFetchStatus.FETCHED, out.status)
        assertEquals(BIG_BODY.toLong(), out.bodyBytes)
        assertEquals(true, out.truncated)
        assertEquals(TRUNCATE_CAP.toInt(), out.body.length)
    }

    @Test
    fun aFetchWhoseDeadlineAlreadyPassedIsATimeout() {
        val bridge = HttpFetchBridgeImpl(advancedScopeFor(server.port), RealAddressResolver())
        val out =
            bridge.fetch(
                HttpFetchRequest(
                    "http://127.0.0.1:${server.port}/hello",
                    "GET",
                    FETCH_BODY_BYTES,
                    System.currentTimeMillis() - 1_000,
                ),
            )
        assertEquals(HttpFetchStatus.TIMEOUT, out.status)
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────

    private fun fetch(
        url: String,
        method: String = "GET",
        maxBodyBytes: Long = FETCH_BODY_BYTES,
        maxRedirects: Int = MAX_REDIRECTS,
    ): HttpFetchRequest = HttpFetchRequest(url, method, maxBodyBytes, futureDeadline(), maxRedirects)

    private fun futureDeadline(): Long = System.currentTimeMillis() + 15_000

    private fun policy(
        profile: SafetyProfile,
        vararg scopes: NetworkOriginScope,
    ): EgressPolicyProvider =
        object : EgressPolicyProvider {
            override fun current(): EgressPolicy = EgressPolicy(profile, scopes.toSet())
        }

    private fun advancedScopeFor(port: Int): EgressPolicyProvider =
        policy(SafetyProfile.ADVANCED, NetworkOriginScope("127.0.0.1", port))

    private fun resolverReturns(vararg addresses: ByteArray): AddressResolver =
        object : AddressResolver {
            override fun resolve(host: String): List<ByteArray> = addresses.toList()
        }

    private fun loopbackV4(): ByteArray = bytes(127, 0, 0, 1)

    private fun metadataV4(): ByteArray = bytes(169, 254, 169, 254)

    private fun bytes(vararg v: Int): ByteArray = v.map { it.toByte() }.toByteArray()

    /** A minimal one-connection-per-request HTTP server bound to 127.0.0.1 on an ephemeral port. */
    private fun startLocalServer(): LocalServer {
        val server = ServerSocket()
        // No setReuseAddress: Android's bionic socket throws EPERM for it, and an ephemeral (port 0)
        // bind has no TIME_WAIT collision to work around anyway.
        server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        val port = server.localPort
        val acceptor =
            Thread {
                while (!server.isClosed) {
                    val client =
                        try {
                            server.accept()
                        } catch (e: Exception) {
                            break
                        }
                    Thread { handleConnection(client) }.start()
                }
            }
        acceptor.isDaemon = true
        acceptor.start()
        return LocalServer(server)
    }

    private fun handleConnection(client: Socket) {
        try {
            val input = client.getInputStream()
            val output = client.getOutputStream()
            val requestLine = readServerLine(input) ?: return
            while (true) {
                val header = readServerLine(input)
                if (header == null || header.isEmpty()) break
            }
            val target = requestLine.split(" ").getOrNull(1) ?: "/"
            val (statusLine, headers, body) = route(target)
            output.write(statusLine.toByteArray(StandardCharsets.US_ASCII))
            for (header in headers) output.write(header.toByteArray(StandardCharsets.US_ASCII))
            output.flush()
            if (body.isNotEmpty()) output.write(body)
            output.flush()
        } catch (e: Exception) {
            // The client may close early (a HEAD whose body is not read, a truncated read); the
            // connection is per-request and closed either way, so a broken pipe is expected here.
        } finally {
            try {
                client.close()
            } catch (e: Exception) {
                // best-effort close
            }
        }
    }

    private fun readServerLine(input: InputStream): String? {
        var first = input.read()
        if (first == -1) return null
        val sb = StringBuilder()
        var b = first
        while (b != -1 && b != '\n'.code) {
            if (b != '\r'.code) sb.append(b.toChar())
            b = input.read()
        }
        return sb.toString()
    }

    private fun route(target: String): Triple<String, List<String>, ByteArray> =
        when (target) {
            "/hello" -> {
                Triple(
                    "HTTP/1.1 200 OK\r\n",
                    listOf("Content-Type: text/plain\r\n", "Content-Length: 11\r\n", "Connection: close\r\n", "\r\n"),
                    "hello world".toByteArray(StandardCharsets.UTF_8),
                )
            }

            "/big" -> {
                val body = "A".repeat(BIG_BODY).toByteArray(StandardCharsets.UTF_8)
                Triple(
                    "HTTP/1.1 200 OK\r\n",
                    listOf(
                        "Content-Type: text/plain\r\n",
                        "Content-Length: ${body.size}\r\n",
                        "Connection: close\r\n",
                        "\r\n",
                    ),
                    body,
                )
            }

            "/redirect" -> {
                Triple(
                    "HTTP/1.1 302 Found\r\n",
                    listOf("Location: /hello\r\n", "Connection: close\r\n", "\r\n"),
                    ByteArray(0),
                )
            }

            "/redirect-loop" -> {
                Triple(
                    "HTTP/1.1 302 Found\r\n",
                    listOf("Location: /redirect-loop\r\n", "Connection: close\r\n", "\r\n"),
                    ByteArray(0),
                )
            }

            else -> {
                Triple(
                    "HTTP/1.1 404 Not Found\r\n",
                    listOf("Content-Length: 0\r\n", "Connection: close\r\n", "\r\n"),
                    ByteArray(0),
                )
            }
        }

    private class LocalServer(
        private val socket: ServerSocket,
    ) {
        val port: Int
            get() = socket.localPort

        fun close() {
            try {
                socket.close()
            } catch (e: Exception) {
                // best-effort close
            }
        }
    }

    private companion object {
        const val BIG_BODY: Int = 10_000
        const val TRUNCATE_CAP: Long = 100
        const val FETCH_BODY_BYTES: Long = 256L * 1024
    }
}
