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
