package com.helix.app.provider

import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelRequest
import com.helix.provider.api.ModelCatalogResult
import com.helix.provider.api.ModelProvider
import com.helix.provider.api.ProbeOutcome
import com.helix.provider.api.ProviderCheckResult
import com.helix.provider.api.ProviderConfig
import kotlinx.coroutines.flow.map

/** Reuses the protocol adapter; static model identity is never presented as an authenticated catalog. */
internal class BuiltInModelProvider(
    private val config: ProviderConfig,
    private val delegate: ModelProvider,
) : ModelProvider by delegate {
    override suspend fun listModels(): ModelCatalogResult = ModelCatalogResult.Unsupported

    override suspend fun contextWindow(model: String): Long? = null

    override suspend fun validateConfiguration(): ProviderCheckResult =
        when (val result = ProviderConnectionCheck.run(config, this, null)) {
            is ProbeOutcome.Ok -> ProviderCheckResult.Ok
            is ProbeOutcome.Failed -> ProviderCheckResult.Failed(result.code, result.detail, result.retryable)
        }

    override fun stream(request: ModelRequest): kotlinx.coroutines.flow.Flow<ModelEvent> {
        require(request.model == BuiltInModel.MODEL_ID) { "Bundled model is fixed" }
        return delegate.stream(request).map { event ->
            // Manual retry remains available; inference/compaction must not retry quota or replay a stream.
            if (event is ModelEvent.Error) event.copy(retryable = false) else event
        }
    }
}
