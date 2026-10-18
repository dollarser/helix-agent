package com.helix.app.provider

import com.helix.core.model.ProviderProvisioningKind
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.repository.ProviderConfigSpec

/** Registers only project-owned metadata, without network calls or changes to existing session bindings. */
internal object BuiltInModelRegistration {
    fun ensureRegistered(storage: HelixStorage) {
        val existing = storage.providerConfigs.find(BuiltInModel.PROVIDER_ID)
        if (existing != null) {
            // Existing rows are validated by the provider service/factory. A corrupt row must not
            // prevent startup or overwrite a persisted binding while other providers remain usable.
            return
        }
        storage.providerConfigs.save(
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
