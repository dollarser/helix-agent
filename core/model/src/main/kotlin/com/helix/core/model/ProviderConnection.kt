package com.helix.core.model

/** Ownership determines management, never network trust or tool permission. */
enum class ProviderProvisioningKind {
    USER_CONFIGURED,
    MANAGED_ACCOUNT,
    ON_DEVICE_ASSET,
}

/** Only network transports have a wire protocol and endpoint. */
sealed interface ProviderTransport {
    data class Network(
        val protocol: ProviderProtocol,
        val endpoint: NormalizedEndpoint,
    ) : ProviderTransport

    data object OnDeviceLocal : ProviderTransport

    val residence: ProviderResidence
        get() =
            when (this) {
                is Network -> endpoint.residence()
                OnDeviceLocal -> ProviderResidence.ON_DEVICE_LOCAL
            }

    /** Transport identity is not a URL; model and verified asset identity belong in higher-level keys. */
    val cacheKey: String
        get() =
            when (this) {
                is Network -> "network:${protocol.name}:${endpoint.full}"
                OnDeviceLocal -> "on-device"
            }
}

sealed interface ProviderAuth {
    data object None : ProviderAuth

    data class Secret(
        val alias: SecretAlias,
    ) : ProviderAuth

    data object ManagedAccount : ProviderAuth
}

/** Closed combinations supported by the application, shared by storage and provider boundaries. */
data class ProviderConnection(
    val provisioning: ProviderProvisioningKind,
    val transport: ProviderTransport,
    val auth: ProviderAuth,
) {
    init {
        require(
            when (provisioning) {
                ProviderProvisioningKind.USER_CONFIGURED -> {
                    transport is ProviderTransport.Network && (auth is ProviderAuth.None || auth is ProviderAuth.Secret)
                }

                ProviderProvisioningKind.MANAGED_ACCOUNT -> {
                    transport is ProviderTransport.Network && auth == ProviderAuth.ManagedAccount
                }

                ProviderProvisioningKind.ON_DEVICE_ASSET -> {
                    transport == ProviderTransport.OnDeviceLocal && auth == ProviderAuth.None
                }
            },
        ) { "Unsupported provider connection combination" }
    }
}

/** Strict row decoding; absent network fields are meaningful only for on-device transport. */
object ProviderConnectionCodec {
    fun decode(
        provisioning: String,
        transport: String,
        auth: String,
        protocol: String?,
        endpoint: String?,
        secretAlias: String?,
    ): ProviderConnection {
        val channel =
            when (transport) {
                "NETWORK" -> {
                    ProviderTransport.Network(
                        ProviderProtocol.parse(requireNotNull(protocol)),
                        NormalizedEndpoint.parse(requireNotNull(endpoint)),
                    )
                }

                "ON_DEVICE_LOCAL" -> {
                    require(protocol == null && endpoint == null) { "Local transport cannot contain network fields" }
                    ProviderTransport.OnDeviceLocal
                }

                else -> {
                    throw IllegalArgumentException("Unknown provider transport")
                }
            }
        val authentication =
            when (auth) {
                "NONE" -> {
                    require(secretAlias == null) { "No-auth transport cannot contain a secret alias" }
                    ProviderAuth.None
                }

                "SECRET" -> {
                    ProviderAuth.Secret(SecretAlias(requireNotNull(secretAlias)))
                }

                "MANAGED_ACCOUNT" -> {
                    require(secretAlias == null) { "Managed account cannot contain a secret alias" }
                    ProviderAuth.ManagedAccount
                }

                else -> {
                    throw IllegalArgumentException("Unknown provider auth")
                }
            }
        return ProviderConnection(ProviderProvisioningKind.valueOf(provisioning), channel, authentication)
    }
}
