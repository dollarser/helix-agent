package com.helix.core.policy

import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderResidence
import com.helix.core.model.SafetyProfile

/**
 * The connection-time SSRF / URL-Policy address classifier (roadmap HXA-066; security doc 7.9).
 *
 * [PolicyEngine] is the decision-time floor (it sees only the host). This object is what the
 * network transport runs AFTER it resolves the host: it classifies every A/AAAA candidate
 * (including IPv4-mapped IPv6) and decides whether the resolved set may be connected to, so a
 * public hostname that rebinds to a loopback/LAN/metadata address — or a scoped LAN host that
 * rebinds to a public one — is refused, not followed. It is pure JVM (raw address bytes, no
 * `InetAddress`) so it is unit-testable on the host and reusable by both the `:tools:android`
 * fetch transport and the device tests.
 */
object SsrfAddressPolicy {
    /**
     * The SSRF class of one resolved address (network-byte-order bytes, 4 for IPv4 or 16 for
     * IPv6). An IPv4-mapped (`::ffff:a.b.c.d`) or deprecated IPv4-compatible (`::a.b.c.d`)
     * IPv6 literal is unwrapped and classified by its embedded IPv4, so it can never masquerade
     * as a public IPv6 address.
     */
    fun classify(raw: ByteArray): IpAddressClass =
        when (raw.size) {
            4 -> classifyV4(raw)
            16 -> classifyV6(raw)
            else -> IpAddressClass.MALFORMED
        }

    /**
     * The whole resolved set for one origin (security doc 7.9: "一次解析并检查全部 A/AAAA 候选地址…
     * 任一候选违反当前 scope 时 fail closed"). Returns the exact address set the transport may
     * connect to ([SsrfCheckResult.Allowed.connectable]) or a stable refusal — never a partial set
     * that still contains a violating candidate.
     */
    @Suppress("ReturnCount") // fail-closed: each guard is a distinct stable denial
    fun check(
        addresses: List<ByteArray>,
        profile: SafetyProfile,
        lanScopes: Set<NetworkOriginScope>,
        endpoint: NormalizedEndpoint,
    ): SsrfCheckResult {
        if (addresses.isEmpty()) {
            return SsrfCheckResult.Denied(SsrfDenialCode.NO_ADDRESSES, "host did not resolve to any address")
        }
        val classified = addresses.map { it to classify(it) }
        if (classified.any { (_, c) -> c == IpAddressClass.CLOUD_METADATA || c == IpAddressClass.MALFORMED }) {
            return SsrfCheckResult.Denied(
                SsrfDenialCode.RESERVED_METADATA,
                "a resolved address is cloud metadata or malformed",
            )
        }
        val lan =
            endpoint.residence() == ProviderResidence.ON_DEVICE_LOOPBACK ||
                endpoint.residence() == ProviderResidence.USER_AUTHORIZED_LAN
        return if (lan) checkLanHost(classified, profile, lanScopes, endpoint) else checkPublicHost(classified)
    }

    /**
     * Re-validation after the socket connects (security doc 7.9: "连接建立后复验 peer address"): the
     * transport hands back the ACTUAL peer address and re-runs the same decision on it alone, so an
     * address that changed between resolve and connect (in-flight DNS rebinding) is refused.
     */
    fun revalidatePeer(
        peer: ByteArray,
        profile: SafetyProfile,
        lanScopes: Set<NetworkOriginScope>,
        endpoint: NormalizedEndpoint,
    ): SsrfCheckResult = check(listOf(peer), profile, lanScopes, endpoint)

    /**
     * A public-intended host (public hostname or bare public IP) must resolve ONLY to public
     * addresses; any loopback/LAN/link-local/reserved candidate is an SSRF / DNS-rebinding attempt.
     */
    private fun checkPublicHost(classified: List<Pair<ByteArray, IpAddressClass>>): SsrfCheckResult {
        if (classified.any { (_, c) -> c != IpAddressClass.PUBLIC }) {
            return SsrfCheckResult.Denied(
                SsrfDenialCode.NON_PUBLIC_ADDRESS,
                "a public host resolved to a non-public address",
            )
        }
        return SsrfCheckResult.Allowed(classified.map { it.first })
    }

    /**
     * A loopback/LAN host is refused under STANDARD outright; under ADVANCED it needs the user's
     * exact pre-created scope for host:port, and even then it must resolve only to loopback/LAN/
     * link-local (never a public or otherwise reserved address).
     */
    @Suppress("ReturnCount") // fail-closed: each guard is a distinct stable denial
    private fun checkLanHost(
        classified: List<Pair<ByteArray, IpAddressClass>>,
        profile: SafetyProfile,
        lanScopes: Set<NetworkOriginScope>,
        endpoint: NormalizedEndpoint,
    ): SsrfCheckResult {
        if (profile == SafetyProfile.STANDARD) {
            return SsrfCheckResult.Denied(
                SsrfDenialCode.LAN_NOT_ALLOWED,
                "LAN/loopback egress is not allowed under STANDARD",
            )
        }
        if (lanScopes.none { it.matches(endpoint.host, endpoint.port) }) {
            return SsrfCheckResult.Denied(
                SsrfDenialCode.SCOPE_VIOLATION,
                "no exact NetworkOriginScope for ${endpoint.origin}",
            )
        }
        if (classified.any { (_, c) -> c == IpAddressClass.PUBLIC || c == IpAddressClass.OTHER_RESERVED }) {
            return SsrfCheckResult.Denied(
                SsrfDenialCode.SCOPE_VIOLATION,
                "a scoped host resolved to a public or reserved address",
            )
        }
        return SsrfCheckResult.Allowed(classified.map { it.first })
    }

    private fun classifyV4(b: ByteArray): IpAddressClass {
        val a = b[0].toInt() and 0xFF
        val bb = b[1].toInt() and 0xFF
        val c = b[2].toInt() and 0xFF
        val d = b[3].toInt() and 0xFF
        return when {
            isCloudMetadata(a, bb, c, d) -> IpAddressClass.CLOUD_METADATA
            a == 127 -> IpAddressClass.LOOPBACK
            a == 169 && bb == 254 -> IpAddressClass.LINK_LOCAL
            isPrivateLan(a, bb) -> IpAddressClass.PRIVATE_LAN
            a in 1..223 -> IpAddressClass.PUBLIC
            else -> IpAddressClass.OTHER_RESERVED
        }
    }

    /** Cloud instance-metadata endpoints: AWS `169.254.169.254`, GCP/Ali `100.100.100.200`. */
    private fun isCloudMetadata(
        a: Int,
        bb: Int,
        c: Int,
        d: Int,
    ): Boolean = (a == 169 && bb == 254 && c == 169 && d == 254) || (a == 100 && bb == 100 && c == 100 && d == 200)

    /** RFC1918 private-LAN prefixes: `10/8`, `172.16/12`, `192.168/16`. */
    private fun isPrivateLan(
        a: Int,
        bb: Int,
    ): Boolean = a == 10 || (a == 172 && bb in 16..31) || (a == 192 && bb == 168)

    @Suppress("ReturnCount") // fail-closed: each guard is a distinct stable classification
    private fun classifyV6(b: ByteArray): IpAddressClass {
        if (b.contentEquals(AZURE_METADATA_V6)) return IpAddressClass.CLOUD_METADATA
        // Well-known single addresses take precedence over the deprecated IPv4-compatible form:
        // `::1` and `::` have their first 96 bits zero, so without this guard they would be
        // misread as an embedded `0.0.0.1` / `0.0.0.0`.
        if ((0..15).all { b[it].toInt() == 0 }) return IpAddressClass.OTHER_RESERVED
        val loopback = (0..14).all { b[it].toInt() == 0 } && (b[15].toInt() and 0xFF) == 1
        if (loopback) return IpAddressClass.LOOPBACK
        val mapped =
            (0..9).all { b[it].toInt() == 0 } && (b[10].toInt() and 0xFF) == 0xFF && (b[11].toInt() and 0xFF) == 0xFF
        val compatible = (0..11).all { b[it].toInt() == 0 }
        if (mapped || compatible) return classifyV4(b.copyOfRange(12, 16))
        val w0 = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
        return when {
            w0 in 0xFE80..0xFE9F -> IpAddressClass.LINK_LOCAL
            w0 in 0xFC00..0xFDFF -> IpAddressClass.PRIVATE_LAN
            w0 in 0x2000..0x3FFF -> IpAddressClass.PUBLIC
            else -> IpAddressClass.OTHER_RESERVED
        }
    }

    private fun bytes(vararg v: Int): ByteArray = v.map { it.toByte() }.toByteArray()

    /** Azure IMDS over IPv6, `fd00:ec2::254` — a cloud-metadata endpoint, never fetchable. */
    private val AZURE_METADATA_V6 =
        bytes(0xfd, 0x00, 0x0e, 0xc2, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0x54)
}

/** The SSRF class of a resolved address; [MALFORMED] is a fail-closed sentinel for wrong-size input. */
enum class IpAddressClass {
    /** Routable public address — the only class a public-intended host may resolve to. */
    PUBLIC,

    /** 127.0.0.0/8, ::1 (data stays on the device). */
    LOOPBACK,

    /** 169.254.0.0/16, fe80::/10 (link-local). */
    LINK_LOCAL,

    /** 10/8, 172.16/12, 192.168/16, ULA fc00::/7. */
    PRIVATE_LAN,

    /** Cloud instance-metadata endpoints (always rejected, even under ADVANCED). */
    CLOUD_METADATA,

    /** Other non-routable / reserved ranges (0/8, multicast, 240/4, ::, IPv6 multicast). */
    OTHER_RESERVED,

    /** Not a 4- or 16-byte address; fail closed. */
    MALFORMED,
}

/** Stable refusal codes for UI, audit and tests (mirrors [PolicyDenialCode]'s style). */
enum class SsrfDenialCode {
    /** The host resolved to nothing. */
    NO_ADDRESSES,

    /** A candidate is a cloud-metadata or malformed address. */
    RESERVED_METADATA,

    /** A public-intended host resolved to a loopback/LAN/link-local/reserved address. */
    NON_PUBLIC_ADDRESS,

    /** A loopback/LAN host reached under STANDARD (no exact scope path). */
    LAN_NOT_ALLOWED,

    /** A loopback/LAN host without a matching exact scope, or that rebinds to a public/reserved IP. */
    SCOPE_VIOLATION,
}

/**
 * The result of a connection-time SSRF check: the exact verified address set the transport may
 * connect to, or a stable refusal. [Allowed.connectable] is the "只把本次已验证地址集合交给
 * transport" set — the transport must connect only to one of these and revalidate the peer.
 */
sealed interface SsrfCheckResult {
    data class Allowed(
        val connectable: List<ByteArray>,
    ) : SsrfCheckResult

    data class Denied(
        val code: SsrfDenialCode,
        val reason: String,
    ) : SsrfCheckResult
}
