package com.helix.core.storage.entity

import com.helix.core.model.ProviderConnection
import com.helix.core.model.ProviderConnectionCodec

/** Decode before consuming a persisted destination or authentication fact. */
fun ProviderConfigEntity.connection(): ProviderConnection =
    ProviderConnectionCodec.decode(
        provisioningKind,
        transportKind,
        authKind,
        protocol,
        endpoint,
        secretAlias,
    )

val ProviderConfigEntity.transportIdentity: String get() = connection().transport.cacheKey
