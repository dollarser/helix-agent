package com.helix.tools.android

import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.SafetyProfile
import com.helix.core.policy.NetworkOriginScope
import com.helix.core.policy.SsrfAddressPolicy
import com.helix.core.policy.SsrfCheckResult
import com.helix.core.policy.SsrfDenialCode
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLContext

/**
 * The trusted egress decision for one fetch, supplied by the APP (never the model): the current
 * [SafetyProfile] plus the user's pre-created exact LAN/loopback [NetworkOriginScope]s. A model URL
 * can read this only indirectly through the transport's refusal — it cannot widen it (roadmap
 * HXA-066: "模型 URL 不能创建 scope").
 */
data class EgressPolicy(
    val profile: SafetyProfile,
    val lanScopes: Set<NetworkOriginScope>,
)

/**
 * The seam the production [HttpFetchBridgeImpl] reads the current [EgressPolicy] from. The app
 * container implements it against its [SafetyProfile] store (the LAN-scopes store lands with
 * HXA-068; until then it is empty, so under ADVANCED no LAN/loopback host is reachable — fail
 * closed). Unit/device tests inject a fake to pin Standard-vs-Advanced and scoped-vs-unscoped.
 */
interface EgressPolicyProvider {
    fun current(): EgressPolicy
}

/**
 * The address-resolution seam. Production is [RealAddressResolver] (the JVM resolver); the device
 * test injects a fake so a public hostname can be made to REBIND to a loopback / metadata address
 * deterministically — the DNS-rebinding and metadata cases the security doc 7.9 requires, without
 * touching /etc/hosts or the network.
 */
interface AddressResolver {
    /**
     * All A/AAAA candidate addresses for [host] as raw bytes (4 for IPv4, 16 for IPv6); empty when
     * it does not resolve.
     */
    fun resolve(host: String): List<ByteArray>
}

/** The production [AddressResolver]: the platform resolver. Any resolution failure is empty (→ the
 * policy's NO_ADDRESSES refusal), never a throw. */
class RealAddressResolver : AddressResolver {
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // a resolution failure is a fail-closed empty set
    override fun resolve(host: String): List<ByteArray> =
        try {
            InetAddress.getAllByName(host).map { it.address }
        } catch (e: Exception) {
            emptyList()
        }
}

/**
 * The production [HttpFetchBridge] (roadmap HXA-066, security doc 7.9): a raw-socket GET/HEAD
 * transport that enforces the connection-time SSRF / URL-Policy the pure-JVM [SsrfAddressPolicy]
 * decides. Per hop it:
 *
 * 1. normalizes the URL fail-closed ([NormalizedEndpoint] — userinfo / query / fragment / non-http(s)
 *    are refused, not followed);
 * 2. resolves EVERY A/AAAA/IPv4-mapped candidate ([AddressResolver]);
 * 3. checks the WHOLE resolved set against the profile + exact scope and hands ONLY the verified
 *    address set ([SsrfCheckResult.Allowed.connectable]) to the connection;
 * 4. connects to one of the verified addresses, then REVALIDATES the actual peer
 *    ([SsrfAddressPolicy.revalidatePeer]) so an address that changed between resolve and connect
 *    (in-flight rebinding) is refused;
 * 5. for https, wraps the connected socket in TLS with the ORIGINAL hostname as SNI/Host so the
 *    certificate is validated against the hostname, never the resolved IP;
 * 6. reads a bounded response, and on a redirect re-runs steps 1–5 on the new origin (each hop is a
 *    fresh egress decision, capped at [MAX_REDIRECTS]).
 *
 * [policyProvider] and [resolver] are injectable seams; the production [AppEgressPolicyProvider] and
 * [RealAddressResolver] are what the app container uses. The port never throws for a network
 * condition — every failure is a stable [HttpFetchOutcome].
 */
@Suppress("TooManyFunctions") // a cohesive low-level HTTP reader: one small primitive per line/byte/framing concern
class HttpFetchBridgeImpl(
    private val policyProvider: EgressPolicyProvider,
    private val resolver: AddressResolver = RealAddressResolver(),
) : HttpFetchBridge {
    override fun fetch(request: HttpFetchRequest): HttpFetchOutcome {
        val policy = policyProvider.current()
        return fetchHops(request, policy, request.url, 0)
    }

    @Suppress("ReturnCount", "SwallowedException") // fail-closed: distinct stable outcomes
    private fun fetchHops(
        request: HttpFetchRequest,
        policy: EgressPolicy,
        currentUrl: String,
        hops: Int,
    ): HttpFetchOutcome {
        if (hops > request.maxRedirects) return refused("redirect-limit", hops - 1)
        val endpoint =
            try {
                NormalizedEndpoint.parse(currentUrl)
            } catch (e: IllegalArgumentException) {
                return refused("invalid-url", hops)
            }
        val check = SsrfAddressPolicy.check(resolver.resolve(endpoint.host), policy.profile, policy.lanScopes, endpoint)
        val allowed =
            check as? SsrfCheckResult.Allowed
                ?: return refused(denialReason((check as SsrfCheckResult.Denied).code), hops)
        return when (val conn = connectAndFetch(request, policy, endpoint, allowed.connectable)) {
            is Conn.Fail -> {
                conn.outcome
            }

            is Conn.Ok -> {
                conn.response.location?.let { location ->
                    resolveRedirect(location, currentUrl)
                        ?.let { fetchHops(request, policy, it, hops + 1) }
                        ?: refused("redirect-refused", hops)
                } ?: fetched(conn.response, currentUrl, hops)
            }
        }
    }

    @Suppress("ReturnCount", "SwallowedException", "TooGenericExceptionCaught") // distinct stable outcomes
    private fun connectAndFetch(
        request: HttpFetchRequest,
        policy: EgressPolicy,
        endpoint: NormalizedEndpoint,
        connectable: List<ByteArray>,
    ): Conn {
        val remaining = request.deadlineMillis - System.currentTimeMillis()
        if (remaining <= 0) return Conn.Fail(timeout(0))
        for (address in connectable) {
            val socket =
                try {
                    connect(address, endpoint.port, remaining)
                } catch (e: SocketTimeoutException) {
                    return Conn.Fail(timeout(0))
                } catch (e: Exception) {
                    continue
                }
            // Connect-time peer revalidation (security doc 7.9): the ACTUAL peer must still satisfy
            // the same decision, so an address that rebound between resolve and connect is refused.
            val peer =
                SsrfAddressPolicy.revalidatePeer(socket.inetAddress.address, policy.profile, policy.lanScopes, endpoint)
            val peerAllowed = peer as? SsrfCheckResult.Allowed
            if (peerAllowed == null) {
                closeQuietly(socket)
                return Conn.Fail(refused(denialReason((peer as SsrfCheckResult.Denied).code), 0))
            }
            val response =
                try {
                    doHttpRequest(socket, endpoint, request)
                } catch (e: SocketTimeoutException) {
                    closeQuietly(socket)
                    return Conn.Fail(timeout(0))
                } catch (e: Exception) {
                    closeQuietly(socket)
                    return Conn.Fail(error("protocol error"))
                }
            closeQuietly(socket)
            return Conn.Ok(response)
        }
        return Conn.Fail(error("connect-failed"))
    }

    /**
     * Wraps the connected socket in TLS for an https origin: [SSLContext.getDefault].socketFactory's
     * [createSocket] performs the handshake using [endpoint.host] as the SNI/expected-DN, so the
     * certificate is validated against the ORIGINAL hostname, never the resolved IP. A raw (unencrypted)
     * socket is returned for an http origin.
     */
    private fun tlsIfNecessary(
        socket: Socket,
        endpoint: NormalizedEndpoint,
    ): Socket =
        if (endpoint.scheme == "https") {
            val factory = SSLContext.getDefault().socketFactory
            factory.createSocket(socket, endpoint.host, endpoint.port, true)
        } else {
            socket
        }

    private fun doHttpRequest(
        socket: Socket,
        endpoint: NormalizedEndpoint,
        request: HttpFetchRequest,
    ): ParsedResponse {
        val secure = tlsIfNecessary(socket, endpoint)
        val readTimeout = minOf(READ_TIMEOUT_MS, request.deadlineMillis - System.currentTimeMillis()).coerceAtLeast(1)
        secure.soTimeout = readTimeout.toInt()
        writeRequest(secure.getOutputStream(), endpoint, request)

        val input = secure.getInputStream()
        val statusLine = readLine(input) ?: throw ProtocolError()
        val status = parseStatus(statusLine)
        val headers = readHeaders(input)

        val (body, bodyBytes, truncated) =
            if (request.method == "HEAD") {
                Triple("", 0L, false)
            } else {
                readBody(input, headers.contentLength, headers.chunked, request.maxBodyBytes)
            }
        return ParsedResponse(status, headers.location, headers.contentType, body, bodyBytes, truncated)
    }

    private fun writeRequest(
        out: OutputStream,
        endpoint: NormalizedEndpoint,
        request: HttpFetchRequest,
    ) {
        val requestLine = "${request.method} ${endpoint.path.ifEmpty { "/" }} HTTP/1.1\r\n"
        out.write(requestLine.toByteArray(StandardCharsets.US_ASCII))
        out.write("Host: ${hostHeader(endpoint)}\r\n".toByteArray(StandardCharsets.US_ASCII))
        out.write("Connection: close\r\n".toByteArray(StandardCharsets.US_ASCII))
        out.write("User-Agent: Helix/0.1\r\n".toByteArray(StandardCharsets.US_ASCII))
        out.write("Accept: */*\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        out.flush()
    }

    private fun parseStatus(statusLine: String): Int =
        statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: throw ProtocolError()

    @Suppress("LoopWithTooManyJumpStatements") // header parser: early-exit on blank line / non-header
    private fun readHeaders(input: InputStream): HeaderSet {
        var location: String? = null
        var contentType = ""
        var contentLength = -1L
        var chunked = false
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            when (name) {
                "location" -> location = value
                "content-type" -> contentType = value
                "content-length" -> contentLength = value.toLongOrNull() ?: -1L
                "transfer-encoding" -> chunked = value.lowercase().contains("chunked")
            }
        }
        return HeaderSet(location, contentType, contentLength, chunked)
    }

    /**
     * Reads the response body bounded to [maxBodyBytes]. With a Content-Length, at most
     * [maxBodyBytes] are read (the full length is still reported so [truncated] is meaningful);
     * otherwise the body is read until the connection closes, cut at the cap. Chunked framing is
     * decoded before bounding.
     */
    @Suppress("ReturnCount")
    private fun readBody(
        input: InputStream,
        contentLength: Long,
        chunked: Boolean,
        maxBodyBytes: Long,
    ): Triple<String, Long, Boolean> {
        if (chunked) {
            val all = readUntilClosed(input, maxBodyBytes)
            val decoded = decodeChunked(all)
            return if (decoded.size > maxBodyBytes) {
                Triple(
                    String(decoded.copyOfRange(0, maxBodyBytes.toInt()), StandardCharsets.UTF_8),
                    decoded.size.toLong(),
                    true,
                )
            } else {
                Triple(String(decoded, StandardCharsets.UTF_8), decoded.size.toLong(), false)
            }
        }
        if (contentLength >= 0) {
            val toRead = minOf(contentLength, maxBodyBytes)
            val buf = ByteArray(toRead.toInt())
            readFully(input, buf)
            val text = String(buf, StandardCharsets.UTF_8)
            return Triple(text, contentLength, contentLength > maxBodyBytes)
        }
        val all = readUntilClosed(input, maxBodyBytes)
        val truncated = all.size >= maxBodyBytes
        val bounded = if (truncated) all.copyOfRange(0, maxBodyBytes.toInt()) else all
        return Triple(String(bounded, StandardCharsets.UTF_8), all.size.toLong(), truncated)
    }

    /** Reads bytes until EOF, never exceeding [cap]. */
    private fun readUntilClosed(
        input: InputStream,
        cap: Long,
    ): ByteArray {
        val buffer = ByteArrayOutputStream()
        val block = ByteArray(8192)
        while (buffer.size() < cap) {
            val want = minOf(block.size.toLong(), cap - buffer.size()).toInt()
            val n = input.read(block, 0, want)
            if (n < 0) break
            buffer.write(block, 0, n)
        }
        return buffer.toByteArray()
    }

    /** Decodes HTTP `transfer-encoding: chunked` framing from raw bytes (best-effort, v1). */
    @Suppress("LoopWithTooManyJumpStatements") // chunked parser: stop at the first malformed frame
    private fun decodeChunked(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var offset = 0
        while (offset < raw.size) {
            val lineEnd = indexOfCrlf(raw, offset)
            if (lineEnd < 0) break
            val sizeHex = String(raw, offset, lineEnd - offset, StandardCharsets.US_ASCII).trim().split(";").first()
            val size = sizeHex.toIntOrNull(16) ?: break
            offset = lineEnd + 2
            if (size == 0) break
            val chunkEnd = minOf(offset + size, raw.size)
            out.write(raw, offset, chunkEnd - offset)
            offset = chunkEnd + 2
        }
        return out.toByteArray()
    }

    private fun indexOfCrlf(
        data: ByteArray,
        from: Int,
    ): Int {
        for (i in from until data.size - 1) {
            if (data[i].toInt() == '\r'.code && data[i + 1].toInt() == '\n'.code) return i
        }
        return -1
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var b = input.read()
        if (b == -1) return null
        while (b != -1 && b != '\n'.code) {
            if (b != '\r'.code) sb.append(b.toChar())
            b = input.read()
        }
        return sb.toString()
    }

    private fun readFully(
        input: InputStream,
        buffer: ByteArray,
    ) {
        var off = 0
        while (off < buffer.size) {
            val n = input.read(buffer, off, buffer.size - off)
            if (n < 0) break
            off += n
        }
    }

    private fun connect(
        address: ByteArray,
        port: Int,
        remainingMillis: Long,
    ): Socket {
        val inet = InetAddress.getByAddress(address)
        val socket = Socket()
        socket.connect(InetSocketAddress(inet, port), minOf(CONNECT_TIMEOUT_MS, remainingMillis).toInt())
        return socket
    }

    /** Resolves a `Location` (absolute or relative) against [baseUrl]; null when it is not usable. */
    @Suppress("SwallowedException", "TooGenericExceptionCaught") // an unresolvable Location is fail-closed null
    private fun resolveRedirect(
        location: String,
        baseUrl: String,
    ): String? {
        val trimmed = location.trim()
        if (trimmed.isEmpty()) return null
        return try {
            URI(baseUrl).resolve(trimmed).toString()
        } catch (e: Exception) {
            null
        }
    }

    private fun hostHeader(endpoint: NormalizedEndpoint): String {
        val expectedPort = if (endpoint.scheme == "https") 443 else 80
        return if (endpoint.port == expectedPort) endpoint.host else "${endpoint.host}:${endpoint.port}"
    }

    private fun denialReason(code: SsrfDenialCode): String =
        when (code) {
            SsrfDenialCode.NO_ADDRESSES -> "no-addresses"
            SsrfDenialCode.RESERVED_METADATA -> "metadata-blocked"
            SsrfDenialCode.NON_PUBLIC_ADDRESS -> "rebind-blocked"
            SsrfDenialCode.LAN_NOT_ALLOWED -> "lan-not-allowed"
            SsrfDenialCode.SCOPE_VIOLATION -> "scope-violation"
        }

    private fun refused(
        reason: String,
        redirectCount: Int,
    ): HttpFetchOutcome = HttpFetchOutcome(HttpFetchStatus.REFUSED, "", 0, "", "", 0, false, redirectCount, reason)

    private fun timeout(redirectCount: Int): HttpFetchOutcome =
        HttpFetchOutcome(HttpFetchStatus.TIMEOUT, "", 0, "", "", 0, false, redirectCount, "timed-out")

    private fun error(reason: String): HttpFetchOutcome =
        HttpFetchOutcome(HttpFetchStatus.ERROR, "", 0, "", "", 0, false, 0, reason)

    private fun fetched(
        response: ParsedResponse,
        finalUrl: String,
        redirectCount: Int,
    ): HttpFetchOutcome =
        HttpFetchOutcome(
            HttpFetchStatus.FETCHED,
            finalUrl,
            response.status,
            response.contentType,
            response.body,
            response.bodyBytes,
            response.truncated,
            redirectCount,
            "",
        )

    @Suppress("SwallowedException", "TooGenericExceptionCaught") // best-effort close
    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (e: Exception) {
            // best-effort close
        }
    }

    private class ProtocolError : Exception()

    private data class ParsedResponse(
        val status: Int,
        val location: String?,
        val contentType: String,
        val body: String,
        val bodyBytes: Long,
        val truncated: Boolean,
    )

    private data class HeaderSet(
        val location: String?,
        val contentType: String,
        val contentLength: Long,
        val chunked: Boolean,
    )

    private sealed interface Conn {
        data class Ok(
            val response: ParsedResponse,
        ) : Conn

        data class Fail(
            val outcome: HttpFetchOutcome,
        ) : Conn
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS: Long = 10_000
        const val READ_TIMEOUT_MS: Long = 20_000
    }
}
