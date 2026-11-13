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
    suspend fun refresh(
        id: String,
        expectedConfig: ProviderConfig? = null,
    ): ModelCatalogResult {
        val observedAccount = account(id)
        if (observedAccount != null && !observedAccount.ready) {
            return ModelCatalogResult.Failed(ModelErrorCode.AUTH, "ACCOUNT_NOT_READY", false)
        }
        val (ticket, observedConfig) =
            gate.begin(id, "catalog") {
                config(id).also { current ->
                    if (expectedConfig != null && !sameProviderEditableConfig(current, expectedConfig)) {
                        throw ProviderDraftSaveConflictException()
                    }
                }
            }
        val discovery = fetch(observedConfig)
        val result = discovery.catalog
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
                    status.modelMetadata.merge(id, observedConfig.transport.cacheKey, discovery.metadata)
                }
            }
        }
        changed()
        return if (accepted) result else ModelCatalogResult.Failed(ModelErrorCode.PROTOCOL, "CATALOG_SUPERSEDED", false)
    }

    private data class Discovery(
        val catalog: ModelCatalogResult,
        val metadata: Map<String, com.helix.provider.api.ModelMetadata> = emptyMap(),
    )

    @Suppress("ReturnCount") // Keep built-in, failed catalog and external metadata paths distinct.
    private suspend fun fetch(config: ProviderConfig): Discovery {
        if (!BuiltInModel.owns(config.id)) {
            val provider = factory.create(config)
            val catalog = provider.listModels()
            if (catalog !is ModelCatalogResult.Listed) return Discovery(catalog)
            val declared = provider.modelMetadata()
            val metadata =
                catalog.models.associateWith { model ->
                    val server =
                        declared[model] ?: com.helix.provider.api
                            .ModelMetadata()
                    server.copy(
                        reasoningEfforts =
                            server.reasoningEfforts
                                ?: OpenAiReasoningMetadata.forModel(config.transport, model)?.reasoningEfforts,
                        purposes = server.purposes.ifEmpty { catalog.purposes[model].orEmpty() },
                        maxOutputTokens = server.maxOutputTokens ?: catalog.outputLimits[model],
                    )
                }
            return Discovery(catalog, metadata)
        }
        builtIn.refresh(true)
        val directory = builtIn.current()
        return Discovery(
            directory.failure?.let { ModelCatalogResult.Failed(it, "CATALOG_FAILED", false) }
                ?: ModelCatalogResult.Listed(directory.models, directory.purposes),
        )
    }
}
