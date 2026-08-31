package com.helix.core.model

/**
 * Where provider request data goes, derived from the normalized actual endpoint only
 * (architecture doc section 6.2, provider doc section 2.5).
 *
 * Residence describes the data destination, not the trustworthiness of the service: a
 * `USER_AUTHORIZED_LAN` endpoint is no more trusted than a `PUBLIC_CLOUD` one, and none of
 * these values lower input/output/auth/prompt-injection protections. The same Ollama/SGLang
 * template can land in any of these classes depending on its actual endpoint; template
 * names, "self-hosted" labels and manual declarations must never substitute for the endpoint
 * check (security doc section 7.2).
 */
enum class ProviderResidence {
    /** Loopback address (127.0.0.0/8, ::1, localhost): data stays on the device. */
    ON_DEVICE_LOOPBACK,

    /**
     * Private-range endpoint (IPv4 10/8, 172.16/12, 192.168/16, 169.254/16; IPv6 ULA fc00::/7
     * and link-local fe80::/10; local DNS names): cleartext HTTP to such an endpoint requires
     * a separate per host:port user authorization (architecture doc section 6.2).
     */
    USER_AUTHORIZED_LAN,

    /** Ordinary public web origin (hostname with a public TLD): data leaves the device to a public service. */
    PUBLIC_CLOUD,

    /**
     * Remote endpoint the classification cannot pin down: bare public IP literals, private
     * DNS suffixes that are not standard local labels, and anything else non-standard.
     * Treated as the least-known destination for gating and UX purposes.
     */
    CUSTOM_REMOTE_UNKNOWN,
}
