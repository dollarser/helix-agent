package com.helix.app.provider

import com.helix.core.model.ProviderAuth
import com.helix.core.model.ProviderConnection
import com.helix.core.model.ProviderHeaders
import com.helix.core.model.ProviderProvisioningKind
import com.helix.core.model.ProviderTransport
import com.helix.core.model.SecretAlias
import com.helix.provider.api.CredentialLookup
import com.helix.provider.api.ModelCatalogResult
import com.helix.provider.api.ProviderConfig

/** A catalog lookup does not create a provider, pass a connection test or persist a key. */
internal suspend fun discoverDraftModels(
    factory: ProviderFactory,
    draft: ProviderDraft,
    key: String?,
    cleartextConfirmed: Boolean,
): ModelCatalogResult {
    require(draft.cleartext == null || cleartextConfirmed)
    val auth = if (key.isNullOrBlank()) ProviderAuth.None else ProviderAuth.Secret(SecretAlias("draft-discovery"))
    val config =
        ProviderConfig(
            "draft-discovery",
            draft.displayName,
            ProviderConnection(
                ProviderProvisioningKind.USER_CONFIGURED,
                ProviderTransport.Network(draft.protocol, draft.endpoint),
                auth,
            ),
            draft.model,
            ProviderHeaders.parse(draft.headersJson),
            "{}",
        )
    return factory.forDraft(CredentialLookup { requireNotNull(key) }).create(config).listModels()
}
