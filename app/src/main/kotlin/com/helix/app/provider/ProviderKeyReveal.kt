package com.helix.app.provider

import com.helix.core.model.SecretAlias
import com.helix.core.storage.HelixStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The UI may reveal only the owner's explicitly configured service credential. */
internal suspend fun revealConfiguredProviderKey(
    storage: HelixStorage,
    providerId: String,
    managed: Boolean,
): String? =
    withContext(Dispatchers.IO) {
        require(!BuiltInModel.owns(providerId) && !managed)
        val entity = storage.providerConfigs.resolve(providerId)
        require(entity.provisioningKind == "USER_CONFIGURED")
        if (entity.authKind != "SECRET") return@withContext null
        entity.secretAlias?.takeUnless { it == ProviderFactory.NO_KEY_ALIAS }?.let {
            storage.secrets.get(SecretAlias(it))
        }
    }
