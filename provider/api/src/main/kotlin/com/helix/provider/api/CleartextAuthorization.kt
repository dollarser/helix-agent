package com.helix.provider.api

import com.helix.core.model.NormalizedEndpoint

/**
 * One explicit user authorization for cleartext HTTP to exactly this `host:port`
 * (provider doc section 2.5: “用户可以为明确的局域网 host 开启 HTTP；授权绑定 host + port，
 * 不是全局 cleartext”).
 *
 * The host is the normalized host form of [NormalizedEndpoint] (lowercase, IPv6 literal
 * without brackets). Port 1..65535. This is the data the developer-build settings store
 * per user action (UI + risk display are HXA-028); the enforcement decision is
 * [isPermitted].
 */
public data class CleartextAuthorization(
    val host: String,
    val port: Int,
) {
    init {
        require(host.isNotBlank()) { "cleartext authorization host must be non-blank" }
        require(port in 1..65535) { "cleartext authorization port must be 1..65535: $port" }
    }

    public companion object {
        /**
         * The provider-doc 2.5 decision table (fail closed):
         *
         * - `https` endpoints are always permitted (no cleartext involved); a TLS failure
         *   is a transport error the stream maps to `Error(TRANSPORT)` — it is NEVER
         *   silently retried over http (“证书错误不得静默降级 HTTP”);
         * - `http` endpoints are permitted ONLY when [authorizations] contains an entry
         *   with the exact normalized (host, port) — the binding is per host AND port,
         *   never a global cleartext switch.
         *
         * This is the app-layer authorization boundary in front of the transport. The
         * developer build additionally carries a network-security-config that permits
         * cleartext at the socket level (static NSEC cannot express a user-entered LAN
         * host); the gate here is what makes the authorization per-host:port.
         */
        public fun isPermitted(
            endpoint: NormalizedEndpoint,
            authorizations: Set<CleartextAuthorization>,
        ): Boolean {
            if (endpoint.scheme == "https") return true
            return CleartextAuthorization(endpoint.host, endpoint.port) in authorizations
        }

        /**
         * The exact authorization an `http` endpoint REQUIRES (null for `https` — no
         * authorization needed). UIs can use this to render “HTTP to host:port requires
         * explicit authorization” before a connection attempt.
         */
        public fun requiredFor(endpoint: NormalizedEndpoint): CleartextAuthorization? =
            if (endpoint.scheme == "https") {
                null
            } else {
                CleartextAuthorization(endpoint.host, endpoint.port)
            }
    }
}
