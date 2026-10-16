package com.helix.core.model

/**
 * The first-hop provider connection, derived from the normalized actual endpoint only
 * (architecture doc section 6.2, provider doc section 2.5).
 *
 * The endpoint cannot establish downstream routing or data residency. A
 * `USER_AUTHORIZED_LAN` endpoint is no more trusted than a `PUBLIC_CLOUD` one, and none of
 * these values lower input/output/auth/prompt-injection protections. The same Ollama/SGLang
 * template can land in any of these classes depending on its actual endpoint; template
 * names, "self-hosted" labels and manual declarations must never substitute for the endpoint
 * check (security doc section 7.2).
 */
enum class ProviderResidence {
    /** Direct app-owned inference runtime; no network endpoint. */
    ON_DEVICE_LOCAL,

    /** Loopback address (127.0.0.0/8, ::1, localhost); it may forward requests elsewhere. */
    ON_DEVICE_LOOPBACK,

    /**
     * Private-range endpoint (IPv4 10/8, 172.16/12, 192.168/16, 169.254/16; IPv6 ULA fc00::/7
     * and link-local fe80::/10; local DNS names). The user selects this endpoint.
     * HTTP is warning-only; residence itself neither grants tool scope nor requires HTTP consent.
     */
    USER_AUTHORIZED_LAN,

    /** Ordinary public web origin (hostname with a public TLD); downstream routing is unknown. */
    PUBLIC_CLOUD,

    /**
     * Remote endpoint the classification cannot pin down: bare public IP literals, private
     * DNS suffixes that are not standard local labels, and anything else non-standard.
     * Treated as the least-known destination for gating and UX purposes.
     */
    CUSTOM_REMOTE_UNKNOWN,
}
