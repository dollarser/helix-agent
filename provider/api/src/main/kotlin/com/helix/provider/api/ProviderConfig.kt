package com.helix.provider.api

import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderHeaders
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ProviderResidence
import com.helix.core.model.SecretAlias

/**
 * Typed, fully validated provider configuration (provider doc section 2, architecture doc
 * section 6.2). This is the value adapters (HXA-021+) and the app consume; the Room row
 * stores the raw string columns, and [fromStorage] is the strict recovery parse for that
 * boundary (ADR-0001 discipline: any malformed stored row fails closed).
 *
 * The credential itself is never part of this value: [secretAlias] is only a SecretStore
 * reference (Keystore-backed, see [SecretAlias]).
 */
data class ProviderConfig(
    val id: String,
    val displayName: String,
    val protocol: ProviderProtocol,
    val endpoint: NormalizedEndpoint,
    val model: String,
    val headers: Map<String, String>,
    val secretAlias: SecretAlias,
    val capabilitySnapshot: String,
) {
    init {
        require(id.isNotEmpty() && id.length <= MAX_ID_LENGTH) { "id must be 1..$MAX_ID_LENGTH chars" }
        require(displayName.isNotBlank() && displayName.length <= MAX_DISPLAY_NAME_LENGTH) {
            "displayName must be 1..$MAX_DISPLAY_NAME_LENGTH non-blank chars"
        }
        require(model.isNotBlank() && model.length <= MAX_MODEL_LENGTH) {
            "model must be 1..$MAX_MODEL_LENGTH non-blank chars"
        }
        require(model.none { it <= ' ' || it == '\u007F' }) { "model contains control characters" }
        require(headers == ProviderHeaders.parse(ProviderHeaders.toStorageString(headers))) {
            "headers failed allowlist validation"
        }
        require(capabilitySnapshot.isNotBlank()) { "capabilitySnapshot must not be blank" }
    }

    /** Data-destination class of [endpoint] — derived from the endpoint only (doc 10 section 2.5). */
    fun residence(): ProviderResidence = endpoint.residence()

    companion object {
        const val MAX_ID_LENGTH = 64
        const val MAX_DISPLAY_NAME_LENGTH = 128
        const val MAX_MODEL_LENGTH = 256

        /**
         * Strict recovery parse from the persisted `provider_configs` columns (doc 9.1).
         * Re-validates every field; any failure is an [IllegalArgumentException].
         */
        @Suppress("LongParameterList") // one parameter per provider_configs column (doc 9.1)
        fun fromStorage(
            id: String,
            displayName: String,
            protocol: String,
            endpoint: String,
            model: String,
            headersJson: String,
            secretAlias: String,
            capabilitySnapshot: String,
        ): ProviderConfig =
            ProviderConfig(
                id = id,
                displayName = displayName,
                protocol = ProviderProtocol.parse(protocol),
                endpoint = NormalizedEndpoint.parse(endpoint),
                model = model,
                headers = ProviderHeaders.parse(headersJson),
                secretAlias = SecretAlias(secretAlias),
                capabilitySnapshot = capabilitySnapshot,
            )
    }
}
