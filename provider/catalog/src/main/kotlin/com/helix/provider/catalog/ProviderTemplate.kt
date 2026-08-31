package com.helix.provider.catalog

import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol

/**
 * Built-in configuration template priority (provider doc section 2.3: P0 首批, P1 跟进).
 */
public enum class TemplatePriority {
    P0,
    P1,
}

/**
 * One built-in provider configuration template (provider doc section 2.2/2.3, HXA-026).
 *
 * A template pre-fills ONLY the protocol, the official endpoint form and the
 * template-level headers; it never contains a model name ("不硬编码会过期的模型 ID")
 * and never contains a key (the credential is a user-provided [com.helix.core.model.SecretAlias]
 * reference, HXA-020/025). "OpenAI-compatible" stays a family of explicit templates
 * (protocol island discipline), never a fuzzy switch.
 *
 * - [defaultEndpoint] is the endpoint's API root in the HXA-025 [com.helix.provider.api.WireModelProvider]
 *   sense (the protocol resource path is appended by the adapter); `null` means the
 *   user must supply the host (generic OpenAI-compatible / self-hosted servers).
 * - [defaultHeaders] are template-level, NON-AUTH headers (attribution etc.). Auth
 *   headers belong to the protocol adapter (Bearer / x-api-key + anthropic-version) and
 *   are never template content.
 * - [credentialRequired] reflects the vendor's API form: self-hosted servers accept an
 *   optional key, public cloud APIs require one.
 * - [notes] are user-facing guidance strings (UI localization is HXA-028).
 */
public data class ProviderTemplate(
    val id: String,
    val displayName: String,
    val priority: TemplatePriority,
    val protocol: ProviderProtocol,
    val defaultEndpoint: NormalizedEndpoint?,
    val defaultHeaders: Map<String, String>,
    val credentialRequired: Boolean,
    val notes: List<String>,
) {
    init {
        require(id.isNotBlank() && id.length <= MAX_ID_LENGTH) { "template id must be 1..$MAX_ID_LENGTH chars" }
        require(displayName.isNotBlank() && displayName.length <= MAX_DISPLAY_NAME_LENGTH) {
            "template displayName must be 1..$MAX_DISPLAY_NAME_LENGTH non-blank chars"
        }
        defaultHeaders.keys.forEach { name ->
            require(name.isNotBlank() && name.length <= MAX_HEADER_NAME_LENGTH) {
                "template header name must be 1..$MAX_HEADER_NAME_LENGTH non-blank chars: $name"
            }
        }
        defaultHeaders.values.forEach { value ->
            require(value.length <= MAX_HEADER_VALUE_LENGTH) {
                "template header value must be 0..$MAX_HEADER_VALUE_LENGTH chars"
            }
        }
        notes.forEach { note ->
            require(note.isNotBlank()) { "template notes must be non-blank" }
        }
    }

    public companion object {
        const val MAX_ID_LENGTH = 64
        const val MAX_DISPLAY_NAME_LENGTH = 128
        const val MAX_HEADER_NAME_LENGTH = 128
        const val MAX_HEADER_VALUE_LENGTH = 512
    }
}
