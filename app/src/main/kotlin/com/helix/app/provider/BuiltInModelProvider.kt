package com.helix.app.provider

import com.helix.provider.api.ModelCatalogResult
import com.helix.provider.api.ModelProvider
import com.helix.provider.api.ProviderCheckResult

/** Reuses the protocol adapter; a catalog reply alone never proves generation authorization. */
internal class BuiltInModelProvider(
    private val delegate: ModelProvider,
    private val responses: ModelProvider? = null,
    private val catalog: suspend () -> ModelCatalogResult,
    private val window: suspend (String) -> Long?,
) : ModelProvider by delegate {
    override fun stream(
        request: com.helix.core.model.ModelRequest,
    ): kotlinx.coroutines.flow.Flow<com.helix.core.model.ModelEvent> {
        if (request.model != BuiltInModel.MODEL_ID) return delegate.stream(request)
        // OFF must be explicit: Luna defaults to medium when the parameter is omitted.
        val normalized =
            request.copy(
                reasoning =
                    if (request.reasoning == com.helix.core.model.ReasoningEffort.OFF) {
                        com.helix.core.model.ReasoningEffort
                            .fromWire("none")
                    } else {
                        request.reasoning
                    },
            )
        // Select before dispatch, never retry a failed request through another protocol.
        return if (normalized.reasoning.name != "NONE") {
            requireNotNull(responses).stream(normalized)
        } else {
            delegate.stream(normalized)
        }
    }

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
