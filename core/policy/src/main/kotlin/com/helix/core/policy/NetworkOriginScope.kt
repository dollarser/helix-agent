package com.helix.core.policy

/**
 * An exact LAN/loopback endpoint the user created from Advanced settings (ADR-0005: "ADVANCED
 * 只有在用户从设置中创建精确 NetworkOriginScope 后才能访问指定 LAN/loopback host + port").
 *
 * Exact only: a single host and port, no wildcards, no prefixes, no ranges. Model, MCP and
 * Skill content can never create or widen one — the type is user state, not a ToolCall
 * parameter.
 */
data class NetworkOriginScope(
    val host: String,
    val port: Int,
) {
    init {
        require(host.length in 1..253) { "host must be 1..253 chars: $host" }
        require(host.none { it.isWhitespace() || it == ':' }) { "host must not contain whitespace or ':': $host" }
        require(host.all { it in '\u0020'..'\u007E' }) { "host must be ASCII printable: $host" }
        require(port in 1..65535) { "port out of range 1..65535: $port" }
    }

    /** Case-insensitive exact host:port key used for rule matching. */
    val matchKey: String
        get() = "$host:$port"

    /** True when an egress host (already normalized to lowercase) and port match exactly. */
    fun matches(
        normalizedHost: String,
        normalizedPort: Int,
    ): Boolean = host.equals(normalizedHost, ignoreCase = true) && port == normalizedPort
}
