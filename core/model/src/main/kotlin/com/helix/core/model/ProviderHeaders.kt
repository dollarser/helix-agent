package com.helix.core.model

import com.helix.core.model.internal.Json
import com.helix.core.model.internal.JsonNode
import com.helix.core.model.internal.parseJson

/**
 * Allowlist validation for user-configured provider request headers (architecture doc
 * section 6.2: the secret is stored as a Keystore alias only; provider doc section 2.6:
 * auth material never enters a provider request as configured data).
 *
 * User-configured headers may carry custom, non-credential metadata (e.g. an org ID header
 * some OpenAI-compatible gateways require). Credential-looking headers are rejected
 * unconditionally: authentication material is resolved from the Keystore by the adapter,
 * never from user configuration.
 *
 * [parse] is fail-closed (ADR-0001 decode contract): every violation throws
 * [IllegalArgumentException].
 */
object ProviderHeaders {
    const val MAX_HEADERS = 16
    const val MAX_NAME_LENGTH = 128
    const val MAX_VALUE_LENGTH = 512
    const val MAX_TOTAL_LENGTH = 4096

    /**
     * Case-insensitive substrings that mark a header as credential-bearing. Intentionally
     * over-approximating (e.g. a hypothetical `X-Keyboard-Layout` is also rejected): an
     * inconvenience beats a shadow credential channel.
     */
    private val CREDENTIAL_NAME_PARTS =
        listOf(
            "auth",
            "cookie",
            "token",
            "key",
            "credential",
            "password",
            "secret",
        )

    /**
     * Exact names (lowercase) managed by the transport stack: the HTTP client sets these
     * itself, and user values would be dropped or conflict.
     */
    private val FORBIDDEN_NAMES =
        setOf(
            "host",
            "connection",
            "upgrade",
            "content-length",
            "transfer-encoding",
            "expect",
            "te",
            "trailer",
        )

    /**
     * Parses and validates [headersJson] (a JSON object of string values). Returns the
     * validated headers with names lowercased and values trimmed, deterministically sorted.
     */
    fun parse(headersJson: String): Map<String, String> {
        require(headersJson.isNotBlank()) { "headersJson must not be blank" }
        val node = parseJson(headersJson)
        require(node is JsonNode.Obj) { "headersJson must be a JSON object" }
        val result = LinkedHashMap<String, String>()
        var total = 0
        for ((rawName, valueNode) in node.entries) {
            require(rawName.length in 1..MAX_NAME_LENGTH) {
                "header name must be 1..$MAX_NAME_LENGTH chars: $rawName"
            }
            require(isTokenName(rawName)) { "header name is not a valid HTTP token: $rawName" }
            val value =
                (valueNode as? JsonNode.Str)?.value
                    ?: throw IllegalArgumentException("header value must be a JSON string: $rawName")
            val trimmed = value.trim()
            require(trimmed.isNotEmpty()) { "header value must not be blank: $rawName" }
            require(trimmed.length <= MAX_VALUE_LENGTH) {
                "header value exceeds $MAX_VALUE_LENGTH chars: $rawName"
            }
            // Internal spaces are legal header value characters (trimmed at the edges);
            // control characters (incl. CR/LF — header injection) and DEL are not.
            require(trimmed.none { it in '\u0000'..'\u001F' || it == '\u007F' }) {
                "header value contains control characters: $rawName"
            }
            val name = rawName.lowercase()
            require(name !in FORBIDDEN_NAMES) {
                "header is managed by the transport and cannot be configured: $rawName"
            }
            require(!name.startsWith("proxy-")) {
                "proxy headers cannot be configured: $rawName"
            }
            require(CREDENTIAL_NAME_PARTS.none { name.contains(it) }) {
                "header looks like credential material and cannot be configured: $rawName"
            }
            require(!result.containsKey(name)) { "duplicate header (case-insensitive): $rawName" }
            result[name] = trimmed
            total += name.length + trimmed.length
        }
        require(result.size <= MAX_HEADERS) {
            "too many headers (max $MAX_HEADERS): ${result.size}"
        }
        require(total <= MAX_TOTAL_LENGTH) {
            "total header size exceeds $MAX_TOTAL_LENGTH chars: $total"
        }
        return result.toSortedMap()
    }

    /** Canonical JSON encoding (sorted names) — the form stored in `provider_configs.headersJson`. */
    fun toStorageString(headers: Map<String, String>): String =
        Json.objectBody(headers.toSortedMap().map { (name, value) -> name to Json.string(value) })

    /** RFC 7230 token: non-empty, no spaces/control characters/delimiters. */
    private fun isTokenName(name: String): Boolean =
        name.isNotEmpty() && name.all { c -> c in '!'..'~' && c !in TOKEN_EXCLUDED }

    private const val TOKEN_EXCLUDED = "\"(),/:;<=>?@[]{}"
}
