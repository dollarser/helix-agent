package com.helix.app.provider

import com.helix.provider.api.ModelCatalogResult
import com.helix.provider.api.ModelProvider
import com.helix.provider.api.ProviderCheckResult

/** Reuses the protocol adapter; a catalog reply alone never proves generation authorization. */
internal class BuiltInModelProvider(
    private val delegate: ModelProvider,
    private val catalog: suspend () -> ModelCatalogResult,
    private val window: suspend (String) -> Long?,
) : ModelProvider by delegate {
    override suspend fun listModels(): ModelCatalogResult = catalog()

    override suspend fun contextWindow(model: String): Long? = window(model)

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
}
