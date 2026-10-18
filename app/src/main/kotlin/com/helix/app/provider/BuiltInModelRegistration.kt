package com.helix.app.provider

import com.helix.app.chat.ConversationLaunchStore
import com.helix.app.internal.LineStore
import com.helix.core.model.ProviderProvisioningKind
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.repository.ProviderConfigSpec
import com.helix.provider.api.ProviderCapabilities

/** Registers only project-owned metadata, without network calls or changes to existing session bindings. */
internal object BuiltInModelRegistration {
    fun ensureRegistered(
        storage: HelixStorage,
        preferences: LineStore,
    ) {
        val existing = storage.providerConfigs.find(BuiltInModel.PROVIDER_ID)
        val freshInstall = storage.providerConfigs.list().isEmpty() && storage.sessions.list().isEmpty()
        ConversationLaunchStore(
            preferences,
        ).seedDraftModel(freshInstall, BuiltInModel.PROVIDER_ID, BuiltInModel.MODEL_ID)
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
                capabilitySnapshot = ProviderCapabilities.toJsonString(BuiltInModel.capabilities),
                provisioningKind = ProviderProvisioningKind.BUNDLED_SERVICE.name,
                authKind = "NONE",
            ),
        )
    }
}
