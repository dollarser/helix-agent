package com.helix.tools.android

import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.SafetyProfile
import com.helix.core.policy.NetworkOriginScope
import com.helix.core.policy.SsrfAddressPolicy
import com.helix.core.policy.SsrfCheckResult
import com.helix.core.policy.SsrfDenialCode
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

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
 * must supply live trusted profile/scope state. The bridge snapshots the initial authorization as
 * a ceiling and rechecks live policy before each connection and before sending HTTP bytes.
 * Unit/device tests inject a fake to pin Standard-vs-Advanced and scoped-vs-unscoped.
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
            com.helix.core.policy.network.NativeNetwork
                .resolve(host)
                .map { it.address }
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
        val policy = policyProvider.current().let { it.copy(lanScopes = it.lanScopes.toSet()) }
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
        for (address in connectable) {
            val remaining = request.deadlineMillis - System.currentTimeMillis()
            if (remaining <= 0) return Conn.Fail(timeout(0))
            // The original decision is a ceiling, not a lease on revoked live permissions.
            policyDenial(connectable, policy, endpoint)?.let { return Conn.Fail(it) }
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
            val denial = policyDenial(listOf(socket.inetAddress.address), policy, endpoint)
            if (denial != null) {
                closeQuietly(socket)
                return Conn.Fail(denial)
            }
            val response =
                try {
                    doHttpRequest(socket, endpoint, request, policy)
                } catch (e: SocketTimeoutException) {
                    closeQuietly(socket)
                    return Conn.Fail(timeout(0))
                } catch (e: Exception) {
                    closeQuietly(socket)
                    return Conn.Fail(error("protocol error"))
                }
            closeQuietly(socket)
            return response
        }
        return Conn.Fail(error("connect-failed"))
    }

    /** Both independent checks must allow: live changes can revoke, never expand this call. */
    private fun policyDenial(
        addresses: List<ByteArray>,
        original: EgressPolicy,
        endpoint: NormalizedEndpoint,
    ): HttpFetchOutcome? {
        val live = policyProvider.current()
        for (policy in listOf(original, live)) {
            val check = SsrfAddressPolicy.check(addresses, policy.profile, policy.lanScopes, endpoint)
            if (check is SsrfCheckResult.Denied) return refused(denialReason(check.code), 0)
        }
        return null
    }

    /** Layer TLS over the pinned connection, retaining the URL host for SNI and HTTPS identity. */
    @Suppress("TooGenericExceptionCaught") // close the layered socket on every failed setup, then rethrow
    private fun tlsIfNecessary(
        socket: Socket,
        endpoint: NormalizedEndpoint,
        request: HttpFetchRequest,
    ): Socket {
        if (endpoint.scheme != "https") return socket
        val secure =
            SSLContext
                .getDefault()
                .socketFactory
                .createSocket(socket, endpoint.host, endpoint.port, true) as SSLSocket
        try {
            secure.sslParameters = secure.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            secure.soTimeout = remainingReadTimeout(request)
            // createSocket alone does not verify identity. Finish authentication before HTTP bytes.
            secure.startHandshake()
            return secure
        } catch (error: Exception) {
            closeQuietly(secure)
            throw error
        }
    }

    private fun remainingReadTimeout(request: HttpFetchRequest): Int {
        val remaining = request.deadlineMillis - System.currentTimeMillis()
        if (remaining <= 0) throw SocketTimeoutException("fetch deadline expired")
        return minOf(READ_TIMEOUT_MS, remaining).toInt()
    }

    private fun doHttpRequest(
        socket: Socket,
        endpoint: NormalizedEndpoint,
        request: HttpFetchRequest,
        policy: EgressPolicy,
    ): Conn =
        tlsIfNecessary(socket, endpoint, request).use { secure ->
            secure.soTimeout = remainingReadTimeout(request)
            policyDenial(listOf(socket.inetAddress.address), policy, endpoint)?.let { return Conn.Fail(it) }
            writeRequest(secure.getOutputStream(), endpoint, request)
            Conn.Ok(HttpFetchResponseParser.read(secure.getInputStream(), request))
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

    @Suppress("TooGenericExceptionCaught") // close a failed connect before rethrowing to the address retry loop
    private fun connect(
        address: ByteArray,
        port: Int,
        remainingMillis: Long,
    ): Socket {
        val inet = InetAddress.getByAddress(address)
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(inet, port), minOf(CONNECT_TIMEOUT_MS, remainingMillis).toInt())
            return socket
        } catch (error: Exception) {
            closeQuietly(socket)
            throw error
        }
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
        response: HttpFetchResponseParser.ParsedResponse,
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

    private sealed interface Conn {
        data class Ok(
            val response: HttpFetchResponseParser.ParsedResponse,
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
