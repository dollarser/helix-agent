package com.helix.app.provider

import com.helix.provider.api.ProviderConfig

/** Display names and picker preferences are not a different connection or a new capability assertion. */
internal fun providerConnectionChanged(
    existing: ProviderConfig,
    draft: ProviderDraft,
    newKey: String?,
): Boolean =
    existing.protocol != draft.protocol || existing.endpoint != draft.endpoint ||
        existing.model != draft.model ||
        existing.headers !=
        com.helix.core.model.ProviderHeaders
            .parse(draft.headersJson) ||
        !newKey.isNullOrBlank()

/** Pure storage decoding shared with the service; it does not admit execution. */
internal fun configFrom(e: com.helix.core.storage.entity.ProviderConfigEntity): ProviderConfig =
    ProviderConfig.fromStorage(
        e.id,
        e.displayName,
        e.protocol,
        e.endpoint,
        e.model,
        e.headersJson,
        e.secretAlias,
        e.capabilitySnapshot,
        e.provisioningKind,
        e.transportKind,
        e.authKind,
    )
