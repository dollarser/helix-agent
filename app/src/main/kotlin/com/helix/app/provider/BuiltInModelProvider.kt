package com.helix.app.provider

import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelRequest
import com.helix.provider.api.ModelCatalogResult
import com.helix.provider.api.ModelProvider
import com.helix.provider.api.ProviderCheckResult
import kotlinx.coroutines.flow.map

/** Reuses the protocol adapter; a catalog reply alone never proves generation authorization. */
internal class BuiltInModelProvider(
    private val delegate: ModelProvider,
) : ModelProvider by delegate {
    override suspend fun listModels(): ModelCatalogResult = delegate.listModels()

    override suspend fun contextWindow(model: String): Long? = null

    override suspend fun validateConfiguration(): ProviderCheckResult =
        when (val result = listModels()) {
            is ModelCatalogResult.Listed -> {
                ProviderCheckResult.Ok
            }

            is ModelCatalogResult.Failed -> {
                ProviderCheckResult.Failed(result.code, result.detail, result.retryable)
            }

            ModelCatalogResult.Unsupported -> {
                ProviderCheckResult.Failed(
                    com.helix.core.model.ModelErrorCode.PROTOCOL,
                    "Model directory unavailable",
                    false,
                )
            }
        }

    override fun stream(request: ModelRequest): kotlinx.coroutines.flow.Flow<ModelEvent> =
        delegate.stream(request).map { event ->
            // Manual retry remains available; inference/compaction must not retry quota or replay a stream.
            if (event is ModelEvent.Error) event.copy(retryable = false) else event
        }
}
