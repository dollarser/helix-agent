package com.helix.provider.api

import com.helix.core.model.NormalizedEndpoint

/** Display-only transport facts. This is neither an authorization nor a connection gate. */
public data class CleartextWarning(
    val host: String,
    val port: Int,
) {
    init {
        require(host.isNotBlank()) { "HTTP warning host must be non-blank" }
        require(port in 1..65535) { "HTTP warning port must be in 1..65535" }
    }

    public companion object {
        /** HTTP remains usable. HTTPS keeps ordinary TLS validation and is never silently downgraded. */
        public fun forEndpoint(endpoint: NormalizedEndpoint): CleartextWarning? =
            if (endpoint.scheme == "http") CleartextWarning(endpoint.host, endpoint.port) else null
    }
}
