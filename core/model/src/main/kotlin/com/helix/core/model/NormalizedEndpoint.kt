package com.helix.core.model

import java.util.Locale

/**
 * A normalized, scheme-pinned provider endpoint (architecture doc section 6.2, provider doc
 * section 2.5).
 *
 * [parse] is fail-closed: any deviation throws [IllegalArgumentException]. Accepted input is
 * exactly `scheme://host[:port][/path]` with
 *
 * - scheme `http` or `https` (compared case-insensitively, stored lowercase);
 * - no userinfo, no query string, no fragment, no whitespace/control characters/backslash;
 * - host a valid IPv4 literal, IPv6 literal (brackets stripped) or ASCII hostname, stored
 *   lowercase; non-ASCII (IDN) hosts are rejected — fail closed, no implicit punycode;
 * - port 1..65535, defaulting to 443 (https) / 80 (http) when omitted;
 * - path stored verbatim (case-sensitive), empty when absent.
 *
 * [residence] derives [ProviderResidence] from this endpoint only — never from a template
 * name or a manual label.
 */
data class NormalizedEndpoint(
    val scheme: String,
    val host: String,
    val port: Int,
    val path: String,
) {
    init {
        require(scheme == "http" || scheme == "https") { "scheme must be http or https: $scheme" }
        require(isValidHost(host)) { "host is not a valid IPv4 address, IPv6 address, or hostname: $host" }
        require(port in 1..65535) { "port out of range 1..65535: $port" }
        require(path.none { it in '\u0000'..'\u001F' || it == '\u007F' || it == '\\' || it == ' ' }) {
            "path contains invalid characters"
        }
    }

    /** Canonical origin without path (the egress-summary origin field, provider doc section 2.6). */
    val origin: String
        get() = "$scheme://$host:$port"

    /** Canonical full endpoint. */
    val full: String
        get() = if (path.isEmpty()) origin else "$origin$path"

    /**
     * Data-destination class derived from the normalized endpoint (see [ProviderResidence]).
     * Decision table (first match wins):
     *
     * | host form | classification |
     * | --- | --- |
     * | `localhost`, 127.0.0.0/8, `::1` | [ProviderResidence.ON_DEVICE_LOOPBACK] |
     * | IPv4 10/8, 172.16/12, 192.168/16, 169.254/16 | [ProviderResidence.USER_AUTHORIZED_LAN] |
     * | IPv6 ULA fc00::/7, link-local fe80::/10 | [ProviderResidence.USER_AUTHORIZED_LAN] |
     * | hostname ending in a private DNS suffix (`.local`, `.localdomain`, `.home.arpa`,
     *   `.lan`, `.intranet`, `.internal`) or a single-label local name |
     *   [ProviderResidence.USER_AUTHORIZED_LAN] |
     * | hostname with any other (public) TLD | [ProviderResidence.PUBLIC_CLOUD] |
     * | bare public IPv4/IPv6 literal | [ProviderResidence.CUSTOM_REMOTE_UNKNOWN] |
     *
     * Note: the Android emulator's host bridge `10.0.2.2` classifies as [ProviderResidence.USER_AUTHORIZED_LAN]
     * (it is a 10/8 address and leaves the device); it is deliberately not treated as loopback.
     */
    fun residence(): ProviderResidence =
        when {
            host == "localhost" -> {
                ProviderResidence.ON_DEVICE_LOOPBACK
            }

            HostAddress.ipv4Words(host) != null -> {
                ipv4Residence()
            }

            HostAddress.ipv6Words(host) != null -> {
                ipv6Residence()
            }

            host.contains('.') && LOCAL_HOST_SUFFIXES.any { host.endsWith(it) } -> {
                ProviderResidence.USER_AUTHORIZED_LAN
            }

            !host.contains('.') -> {
                ProviderResidence.USER_AUTHORIZED_LAN
            }

            else -> {
                ProviderResidence.PUBLIC_CLOUD
            }
        }

    private fun ipv4Residence(): ProviderResidence {
        val v4 = HostAddress.ipv4Words(host) ?: return ProviderResidence.CUSTOM_REMOTE_UNKNOWN
        return when {
            v4[0] == 127 -> ProviderResidence.ON_DEVICE_LOOPBACK
            v4[0] == 10 -> ProviderResidence.USER_AUTHORIZED_LAN
            v4[0] == 172 && v4[1] in 16..31 -> ProviderResidence.USER_AUTHORIZED_LAN
            v4[0] == 192 && v4[1] == 168 -> ProviderResidence.USER_AUTHORIZED_LAN
            v4[0] == 169 && v4[1] == 254 -> ProviderResidence.USER_AUTHORIZED_LAN
            else -> ProviderResidence.CUSTOM_REMOTE_UNKNOWN
        }
    }

    private fun ipv6Residence(): ProviderResidence {
        val words = HostAddress.ipv6Words(host) ?: return ProviderResidence.CUSTOM_REMOTE_UNKNOWN
        return when {
            words[0] == 0L && words.drop(1).take(6).all { it == 0L } && words[7] == 1L -> {
                ProviderResidence.ON_DEVICE_LOOPBACK
            }

            // fe80::/10 link-local, fc00::/7 unique local (first 16-bit word range)
            words[0] in 0xFE80L..0xFE9FL -> {
                ProviderResidence.USER_AUTHORIZED_LAN
            }

            words[0] in 0xFC00L..0xFDFFL -> {
                ProviderResidence.USER_AUTHORIZED_LAN
            }

            else -> {
                ProviderResidence.CUSTOM_REMOTE_UNKNOWN
            }
        }
    }

    companion object {
        private const val DEFAULT_HTTP_PORT = 80
        private const val DEFAULT_HTTPS_PORT = 443

        private val LOCAL_HOST_SUFFIXES =
            listOf(
                ".local",
                ".localdomain",
                ".home.arpa",
                ".lan",
                ".intranet",
                ".internal",
            )

        fun parse(raw: String): NormalizedEndpoint {
            require(raw.isNotEmpty()) { "endpoint must not be empty" }
            val sep = raw.indexOf("://")
            require(sep > 0) { "endpoint must start with 'scheme://' (http or https)" }
            val scheme = raw.substring(0, sep).lowercase(Locale.ROOT)
            require(scheme == "http" || scheme == "https") {
                "endpoint scheme must be http or https: $scheme"
            }
            val remainder = raw.substring(sep + "://".length)
            require(remainder.isNotEmpty()) { "endpoint must contain a host" }
            val slash = remainder.indexOf('/')
            val authority = if (slash >= 0) remainder.substring(0, slash) else remainder
            val path = if (slash >= 0) remainder.substring(slash) else ""
            requireValidSections(authority, path)
            val (hostText, portText) = splitAuthority(authority)
            val host = hostText.lowercase(Locale.ROOT)
            require(host.isNotEmpty()) { "endpoint must contain a host" }
            require(isValidHost(host)) {
                "endpoint host is not a valid IPv4 address, IPv6 address, or hostname: $host"
            }
            return NormalizedEndpoint(scheme, host, parsePort(scheme, portText), path)
        }

        private fun requireValidSections(
            authority: String,
            path: String,
        ) {
            require(authority.none { it in '\u0000'..'\u001F' || it == '\u007F' || it == '\\' || it == ' ' }) {
                "endpoint authority contains invalid characters"
            }
            require(path.none { it in '\u0000'..'\u001F' || it == '\u007F' || it == '\\' || it == ' ' }) {
                "endpoint path contains invalid characters"
            }
            require(!authority.contains('@')) { "endpoint must not embed user credentials" }
            require(!path.contains('?')) { "endpoint must not contain a query string" }
            require(!path.contains('#')) { "endpoint must not contain a fragment" }
        }

        /** Splits `host[:port]` (or `[ipv6][:port]`) into (host, portText-or-null). */
        private fun splitAuthority(authority: String): Pair<String, String?> {
            val close = authority.indexOf(']')
            if (authority.startsWith("[")) {
                require(close >= 2) { "endpoint has an unterminated IPv6 host" }
                val after = authority.substring(close + 1)
                val portText =
                    when {
                        after.isEmpty() -> null
                        after.startsWith(":") -> after.substring(1)
                        else -> throw IllegalArgumentException("endpoint has text after an IPv6 host")
                    }
                require(portText == null || portText.isNotEmpty()) { "endpoint has an empty port" }
                return authority.substring(1, close) to portText
            }
            val colon = authority.lastIndexOf(':')
            return if (colon < 0) authority to null else authority.substring(0, colon) to authority.substring(colon + 1)
        }

        private fun parsePort(
            scheme: String,
            portText: String?,
        ): Int {
            if (portText == null) return if (scheme == "https") DEFAULT_HTTPS_PORT else DEFAULT_HTTP_PORT
            require(portText.isNotEmpty() && portText.all { it in '0'..'9' } && portText.length <= 5) {
                "endpoint port must be a decimal number in 1..65535"
            }
            require(portText.first() != '0') { "endpoint port must not have a leading zero" }
            val port = portText.toInt()
            require(port in 1..65535) { "endpoint port out of range 1..65535: $port" }
            return port
        }

        /** True for IPv4 dotted quads, IPv6 literals, or ASCII hostnames. */
        private fun isValidHost(host: String): Boolean =
            HostAddress.ipv4Words(host) != null || HostAddress.ipv6Words(host) != null || isHostname(host)

        private fun isHostname(host: String): Boolean {
            if (host.isEmpty() || host.length > 253 || host.endsWith(".")) return false
            return host.split(".").all { label ->
                label.isNotEmpty() &&
                    label.length <= 63 &&
                    (label.first() in 'a'..'z' || label.first() in '0'..'9') &&
                    (label.last() in 'a'..'z' || label.last() in '0'..'9') &&
                    label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
            }
        }
    }
}

/** File-private IPv4/IPv6 literal parsing shared by [NormalizedEndpoint] validation. */
private object HostAddress {
    private const val HEX_DIGITS = "0123456789abcdef"

    /** Parses an IPv4 dotted quad; rejects empty parts, leading zeros, non-digits, >255. */
    fun ipv4Words(host: String): IntArray? {
        val parts = host.split(".")
        if (parts.size != 4) return null
        val words = IntArray(4)
        val valid = parts.indices.all { i -> parseOctet(parts[i], words, i) }
        return if (valid) words else null
    }

    private fun parseOctet(
        part: String,
        words: IntArray,
        index: Int,
    ): Boolean {
        val value = parseOctetValue(part)
        if (value == null) return false
        words[index] = value
        return value <= 255
    }

    /** Decimal octet without leading zeros; null when malformed. */
    private fun parseOctetValue(part: String): Int? =
        if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' }) {
            null
        } else if (part.length > 1 && part.first() == '0') {
            null
        } else {
            part.toInt()
        }

    /**
     * Parses a full IPv6 literal (with `::` compression and an optional trailing embedded
     * IPv4) into 8 16-bit words; null when the literal is malformed. Input must be
     * lowercase (callers normalize before validating).
     */
    fun ipv6Words(host: String): LongArray? {
        val text = rewriteEmbeddedV4(host)
        if (text == null) return null
        return if (text.contains("::")) compressedWords(text) else fullWords(text)
    }

    /**
     * Rewrites a trailing embedded IPv4 as the two hex words it represents, so the
     * remaining string is plain hex groups (with at most one `::` compression); null
     * when the trailing quad is malformed.
     */
    private fun rewriteEmbeddedV4(host: String): String? {
        if (!host.contains('.')) return host
        val sep = host.lastIndexOf(':')
        val quad = if (sep == -1) null else ipv4Words(host.substring(sep + 1))
        return quad?.let {
            val hi = it[0].toLong() shl 8 or it[1].toLong()
            val lo = it[2].toLong() shl 8 or it[3].toLong()
            host.substring(0, sep + 1) + String.format(Locale.ROOT, "%04x:%04x", hi, lo)
        }
    }

    /** Parses a `::`-compressed literal (either side may be empty); null when malformed. */
    private fun compressedWords(text: String): LongArray? {
        if (text.indexOf("::") != text.lastIndexOf("::")) return null
        val parts = text.split("::", limit = 2)
        return parseGroups(parts[0])?.let { left ->
            parseGroups(parts[1])?.let { right ->
                if (left.size + right.size > 7) {
                    null
                } else {
                    val full = LongArray(8)
                    left.forEachIndexed { i, w -> full[i] = w }
                    right.forEachIndexed { i, w -> full[8 - right.size + i] = w }
                    full
                }
            }
        }
    }

    /** Parses an uncompressed literal (exactly 8 groups); null when malformed. */
    private fun fullWords(text: String): LongArray? {
        val groups = parseGroups(text)
        if (groups == null || groups.size != 8) return null
        val full = LongArray(8)
        groups.forEachIndexed { i, w -> full[i] = w }
        return full
    }

    /** Splits a group segment on `:`; empty segment yields an empty list. */
    private fun parseGroups(segment: String): List<Long>? {
        if (segment.isEmpty()) return emptyList()
        val words = ArrayList<Long>(segment.length / 2 + 1)
        val ok = segment.split(":").all { appendGroup(words, it) }
        return if (ok) words else null
    }

    private fun appendGroup(
        words: ArrayList<Long>,
        group: String,
    ): Boolean {
        if (group.isEmpty() || group.length > 4 || group.any { it !in HEX_DIGITS }) return false
        words.add(group.toLong(16))
        return true
    }
}
