package com.helix.app.provider

import com.helix.core.model.ProviderProvisioningKind
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.repository.ProviderConfigSpec

/** Registers only project-owned metadata, without network calls or changes to existing session bindings. */
internal object BuiltInModelRegistration {
    fun ensureRegistered(storage: HelixStorage) {
        val existing = storage.providerConfigs.find(BuiltInModel.PROVIDER_ID)
        if (existing != null) {
            // Refresh the project-owned default, not user-selected session/draft bindings.
            // Invalid configurations remain visible to the normal validation path.
            if (runCatching { BuiltInModel.validate(configFrom(existing)) }.isFailure ||
                existing.model == BuiltInModel.MODEL_ID
            ) {
                return
            }
        }
        storage.providerConfigs.overwrite(
            ProviderConfigSpec(
                id = BuiltInModel.PROVIDER_ID,
                displayName = "Helix",
                protocol =
                    BuiltInModel.connection.let {
                        (it.transport as com.helix.core.model.ProviderTransport.Network)
                            .protocol
                    },
                endpoint = BuiltInModel.API_ROOT,
                model = BuiltInModel.MODEL_ID,
                headersJson = "{}",
                secretAlias = null,
                capabilitySnapshot = "{}",
                provisioningKind = ProviderProvisioningKind.BUNDLED_SERVICE.name,
                authKind = "NONE",
            ),
        )
    }
}
