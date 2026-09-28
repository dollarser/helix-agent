package com.helix.provider.api

import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderAuth
import com.helix.core.model.ProviderConnection
import com.helix.core.model.ProviderConnectionCodec
import com.helix.core.model.ProviderHeaders
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ProviderProvisioningKind
import com.helix.core.model.ProviderResidence
import com.helix.core.model.ProviderTransport
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
    val connection: ProviderConnection,
    val model: String,
    val headers: Map<String, String>,
    val capabilitySnapshot: String,
) {
    val transport: ProviderTransport get() = connection.transport
    val provisioning: ProviderProvisioningKind get() = connection.provisioning
    val auth: ProviderAuth get() = connection.auth
    val network: ProviderTransport.Network get() =
        requireNotNull(transport as? ProviderTransport.Network) {
            "Network configuration required"
        }
    val protocol: ProviderProtocol get() = network.protocol
    val endpoint: NormalizedEndpoint get() = network.endpoint
    val secretAlias: SecretAlias get() =
        when (val source = auth) {
            is ProviderAuth.Secret -> source.alias
            else -> throw IllegalArgumentException("Secret authentication required")
        }

    constructor(
        id: String,
        displayName: String,
        protocol: ProviderProtocol,
        endpoint: NormalizedEndpoint,
        model: String,
        headers: Map<String, String>,
        secretAlias: SecretAlias,
        capabilitySnapshot: String,
    ) : this(
        id,
        displayName,
        ProviderConnection(
            ProviderProvisioningKind.USER_CONFIGURED,
            ProviderTransport.Network(protocol, endpoint),
            ProviderAuth.Secret(secretAlias),
        ),
        model,
        headers,
        capabilitySnapshot,
    )

    init {
        require(transport is ProviderTransport.Network || headers.isEmpty()) { "Local transport cannot carry headers" }

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
    fun residence(): ProviderResidence = transport.residence

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
            protocol: String?,
            endpoint: String?,
            model: String,
            headersJson: String,
            secretAlias: String?,
            capabilitySnapshot: String,
            provisioningKind: String = "USER_CONFIGURED",
            transportKind: String = "NETWORK",
            authKind: String = "SECRET",
        ): ProviderConfig =
            ProviderConfig(
                id = id,
                displayName = displayName,
                connection =
                    ProviderConnectionCodec.decode(
                        provisioningKind,
                        transportKind,
                        authKind,
                        protocol,
                        endpoint,
                        secretAlias,
                    ),
                model = model,
                headers = ProviderHeaders.parse(headersJson),
                capabilitySnapshot = capabilitySnapshot,
            )
    }
}
