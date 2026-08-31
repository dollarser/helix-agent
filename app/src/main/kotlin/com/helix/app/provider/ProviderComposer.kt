package com.helix.app.provider

import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderHeaders
import com.helix.core.model.ProviderProtocol
import com.helix.provider.api.CleartextAuthorization
import com.helix.provider.catalog.ProviderTemplate
import com.helix.provider.catalog.ProviderTemplateCatalog

/**
 * A fully validated, not-yet-persisted provider (HXA-028). Built by
 * [ProviderComposer] from a template + user input; every field is already in
 * its normative form:
 *
 * - [endpoint] is a [NormalizedEndpoint] (parsed fail-closed: only http/https,
 *   no userinfo/query/fragment/IDN) — [residence] and [origin] are DERIVED from
 *   it, never from the template name or a manual label (doc 10 section 2.5,
 *   FR-LLM-009);
 * - [headersJson] already passed the [ProviderHeaders] allowlist (<=16 headers,
 *   credential-looking and transport-reserved names rejected);
 * - [cleartext] is non-null exactly when the endpoint is http — the UI must
 *   show the host:port risk display and obtain the user's explicit
 *   per-host:port confirmation before the provider can be saved
 *   (doc 10 section 2.5; ADR-0005: no global cleartext switch).
 *
 * No secret material lives here: the API key stays in the Android Keystore
 * under an alias (NFR-007); [credentialRequired] tells the UI whether a key
 * must be supplied.
 */
data class ProviderDraft(
    val templateId: String?,
    val displayName: String,
    val protocol: ProviderProtocol,
    val endpoint: NormalizedEndpoint,
    val model: String,
    val headersJson: String,
    val credentialRequired: Boolean,
    val cleartext: CleartextAuthorization?,
    val templateNotes: List<String>,
) {
    /** Data-residence class of the endpoint (doc 02 section 9.1 / ADR-0005). */
    val residence: com.helix.core.model.ProviderResidence
        get() = endpoint.residence()

    /** Canonical origin (scheme://host[:port], no path) for EgressSummary display. */
    val origin: String
        get() = endpoint.origin

    /** True when this provider talks cleartext http — the UI risk display applies. */
    val isCleartext: Boolean
        get() = cleartext != null
}

/**
 * Compose outcome. [Rejected.reason] is a USER-VISIBLE Chinese sentence
 * (doc 02 section 13: exception messages are never shown raw); the UI shows it
 * as-is and takes no further action.
 */
sealed interface ComposeOutcome {
    data class Ok(
        val draft: ProviderDraft,
    ) : ComposeOutcome

    data class Rejected(
        val reason: String,
    ) : ComposeOutcome
}

/**
 * Pure composition of a [ProviderDraft] from a catalog template + form input.
 * All validation is fail-closed and returns [ComposeOutcome.Rejected] with a
 * user-visible reason instead of throwing (the form re-renders the reason).
 */
object ProviderComposer {
    /**
     * Composes a [ProviderDraft] from a template + form input. Single
     * fail-closed result: the FIRST violation wins (form -> endpoint ->
     * header merge -> header allowlist); [ComposeOutcome.Rejected.reason] is
     * always a user-visible Chinese sentence (doc 02 section 13: raw
     * exception messages are never shown).
     *
     * [extraHeaders] are the user's custom headers; they are merged over the
     * template's [ProviderTemplate.defaultHeaders] case-insensitively (a
     * collision is a rejection — the same rule as the HXA-025 wire merge),
     * then the merged set passes the [ProviderHeaders] allowlist.
     */
    fun compose(
        template: ProviderTemplate,
        displayName: String,
        rawEndpoint: String,
        model: String,
        extraHeaders: Map<String, String>,
    ): ComposeOutcome {
        val error = firstRejection(template, displayName, rawEndpoint, model, extraHeaders)
        if (error != null) return ComposeOutcome.Rejected(error)
        val endpoint = parseEndpoint(rawEndpoint)
        val headersJson = headersJsonFor(template.defaultHeaders, extraHeaders)
        return ComposeOutcome.Ok(
            ProviderDraft(
                templateId = template.id,
                displayName = displayName,
                protocol = template.protocol,
                endpoint = endpoint!!,
                model = model,
                headersJson = headersJson!!,
                credentialRequired = template.credentialRequired,
                cleartext = CleartextAuthorization.requiredFor(endpoint),
                templateNotes = template.notes,
            ),
        )
    }

    /**
     * The first validation failure in fixed order (form -> endpoint -> header
     * merge -> header allowlist), as a user-visible Chinese reason; null when
     * the form composes. Each step is independent and fail-closed.
     */
    private fun firstRejection(
        template: ProviderTemplate,
        displayName: String,
        rawEndpoint: String,
        model: String,
        extraHeaders: Map<String, String>,
    ): String? =
        listOf(
            {
                if (displayName.isBlank() ||
                    displayName.length > MAX_DISPLAY_NAME
                ) {
                    "名称需为 1–$MAX_DISPLAY_NAME 个字符"
                } else {
                    null
                }
            },
            {
                if (model.isBlank() || model.length > MAX_MODEL || model.any { it.isISOControl() }) {
                    "模型 ID 需为 1–$MAX_MODEL 个字符且不含控制字符"
                } else {
                    null
                }
            },
            { if (parseEndpoint(rawEndpoint) == null) "Endpoint 需为 http/https 地址（不含用户名/密码、query 或片段）" else null },
            {
                if (mergeHeaders(template.defaultHeaders, extraHeaders) ==
                    null
                ) {
                    "自定义 header 与模板 header 冲突（同名不同值）"
                } else {
                    null
                }
            },
            {
                if (headersJsonFor(template.defaultHeaders, extraHeaders) == null) {
                    "header 不允许：凭据样名称（auth/cookie/token/key 等）与传输保留名被拒绝"
                } else {
                    null
                }
            },
        ).firstNotNullOfOrNull { it() }

    /**
     * The parse failures below are INTENTIONALLY converted into a null marker:
     * the composer's contract is a user-visible rejection reason, never an
     * exception or its raw message (doc 02 section 13), so the exception
     * object carries nothing worth keeping after the reason is chosen.
     */
    @Suppress("SwallowedException")
    private fun parseEndpoint(raw: String): NormalizedEndpoint? =
        try {
            NormalizedEndpoint.parse(raw)
        } catch (e: IllegalArgumentException) {
            null
        }

    @Suppress("SwallowedException")
    private fun mergeHeaders(
        base: Map<String, String>,
        override: Map<String, String>,
    ): Map<String, String>? =
        try {
            mergeCaseInsensitive(base, override)
        } catch (e: IllegalArgumentException) {
            null
        }

    /**
     * Validates the merged headers through the [ProviderHeaders] allowlist and
     * returns the canonical storage JSON — or null when any header is
     * disallowed (credential-looking names, transport reservations, bad
     * characters, >16 headers).
     */
    @Suppress("SwallowedException")
    private fun headersJsonFor(
        base: Map<String, String>,
        override: Map<String, String>,
    ): String? =
        try {
            ProviderHeaders.toStorageString(
                ProviderHeaders.parse(headerMapToStorageJson(mergeCaseInsensitive(base, override))),
            )
        } catch (e: IllegalArgumentException) {
            null
        }

    /** Resolves a catalog template by id (unknown ids are a form error, not a crash). */
    fun resolveTemplate(id: String): ProviderTemplate? = ProviderTemplateCatalog.byId(id)

    /** Merges [override] over [base] case-insensitively; same name different value throws IAE. */
    private fun mergeCaseInsensitive(
        base: Map<String, String>,
        override: Map<String, String>,
    ): Map<String, String> {
        val merged = LinkedHashMap<String, String>()
        for ((name, value) in base) {
            merged[name.lowercase()] = value
        }
        for ((name, value) in override) {
            val key = name.lowercase()
            val previous = merged[key]
            require(previous == null || previous == value) { "header collision for $name" }
            merged[key] = value
        }
        return merged
    }

    private const val MAX_DISPLAY_NAME = 128
    private const val MAX_MODEL = 256

    /**
     * Encodes the merged header map as the storage JSON form
     * (`ProviderHeaders.parse` expects a JSON object string). Names must be
     * RFC 7230 tokens (no quotes/backslashes/control characters), which
     * keeps the hand-rolled encoding total and fail-closed; anything else
     * is rejected before JSON assembly (the allowlist in
     * [ProviderHeaders.parse] then re-validates). Kept file-private: the
     * UI never builds header JSON by hand.
     */
    fun headerMapToStorageJson(headers: Map<String, String>): String {
        val parts =
            headers.entries
                .sortedBy { it.key }
                .joinToString(",") { (name, value) ->
                    require(HEADER_NAME_PATTERN.matches(name)) { "invalid header name: $name" }
                    require(value.none { it.isISOControl() || it == '"' }) {
                        "invalid header value for $name"
                    }
                    "\"$name\":\"${value.replace("\\", "\\\\")}\""
                }
        return "{$parts}"
    }

    private val HEADER_NAME_PATTERN: Regex = Regex("[a-z0-9!#$%&'*+.^_`|~-]{1,128}")
}
