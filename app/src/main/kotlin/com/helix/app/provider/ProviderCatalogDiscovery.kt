package com.helix.app.provider

import com.helix.core.model.ModelErrorCode
import com.helix.provider.api.ModelCatalogResult
import com.helix.provider.api.ProviderConfig

/** Publishes one complete directory under the same config/account generation that requested it. */
internal class ProviderCatalogDiscovery(
    private val factory: ProviderFactory,
    private val gate: ProviderProbeGate,
    private val config: suspend (String) -> ProviderConfig,
    private val account: suspend (String) -> ManagedAccountSnapshot?,
    private val builtIn: BuiltInModelDiscovery,
    private val status: ProviderTestStatusStore,
    private val changed: () -> Unit,
) {
    suspend fun refresh(id: String): ModelCatalogResult {
        val observedAccount = account(id)
        if (observedAccount != null && !observedAccount.ready) {
            return ModelCatalogResult.Failed(ModelErrorCode.AUTH, "ACCOUNT_NOT_READY", false)
        }
        val (ticket, observedConfig) = gate.begin(id, "catalog") { config(id) }
        val result = fetch(observedConfig)
        val sameAccount = account(id) == observedAccount
        var accepted = false
        gate.publish(id, ticket) {
            if (sameAccount &&
                config(id).copy(capabilitySnapshot = observedConfig.capabilitySnapshot) == observedConfig
            ) {
                accepted = true
                if (result is ModelCatalogResult.Listed) {
                    status.modelEvidence.catalog(id, observedConfig.transport.cacheKey, result.models)
                    status.modelMetadata.catalog(id, observedConfig.transport.cacheKey, result)
                }
            }
        }
        changed()
        return if (accepted) result else ModelCatalogResult.Failed(ModelErrorCode.PROTOCOL, "CATALOG_SUPERSEDED", false)
    }

    private suspend fun fetch(config: ProviderConfig): ModelCatalogResult {
        if (!BuiltInModel.owns(config.id)) return factory.create(config).listModels()
        builtIn.refresh(true)
        val directory = builtIn.current()
        return directory.failure?.let { ModelCatalogResult.Failed(it, "CATALOG_FAILED", false) }
            ?: ModelCatalogResult.Listed(directory.models, directory.purposes)
    }
}
