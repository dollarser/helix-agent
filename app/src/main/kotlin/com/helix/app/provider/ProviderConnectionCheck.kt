package com.helix.app.provider

import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.provider.api.CapabilitySource
import com.helix.provider.api.ModelCatalogResult
import com.helix.provider.api.ModelProvider
import com.helix.provider.api.ProbeOutcome
import com.helix.provider.api.ProviderCapabilities
import com.helix.provider.api.ProviderConfig
import kotlinx.coroutines.flow.toList

internal object ProviderConnectionCheck {
    data class Observation(
        val outcome: ProbeOutcome,
        val generationAttempted: Boolean,
        val sourceFailure: Boolean = false,
    )

    suspend fun run(
        config: ProviderConfig,
        provider: ModelProvider,
        previous: ProviderCapabilities?,
    ): ProbeOutcome = inspect(config, provider, previous).outcome

    suspend fun inspect(
        config: ProviderConfig,
        provider: ModelProvider,
        previous: ProviderCapabilities?,
        verifyGeneration: Boolean = false,
    ): Observation {
        val accountCatalog = (provider as? SubscriptionConnectionProvider)?.connectionCatalog()
        val catalog = accountCatalog ?: provider.listModels()
        val shortCircuit: ProbeOutcome? =
            when {
                catalog is ModelCatalogResult.Failed -> {
                    ProbeOutcome.Failed(2, catalog.code, catalog.detail, catalog.retryable)
                }

                !verifyGeneration && accountCatalog is ModelCatalogResult.Listed &&
                    accountCatalog.models.isNotEmpty() -> {
                    connected(previous, catalog)
                }

                else -> {
                    null
                }
            }
        if (shortCircuit != null) return Observation(shortCircuit, false, catalog is ModelCatalogResult.Failed)
        // Exactly one ordinary, short generation. No tools, images or explicit effort.
        val events =
            provider
                .stream(
                    ModelRequest(
                        model = config.model,
                        messages = listOf(ModelMessage(ModelRole.USER, "Reply only OK.")),
                        // Native thinking may consume output tokens before emitting any delta.
                        maxOutputTokens = 2048,
                    ),
                ).toList()
        val error = events.filterIsInstance<ModelEvent.Error>().firstOrNull()
        val outcome =
            if (error != null) {
                ProbeOutcome.Failed(3, error.code, "connection reply failed", error.retryable)
            } else if ((events.lastOrNull { it !is ModelEvent.Usage } as? ModelEvent.Completed)?.finishReason ==
                "length" &&
                events.none { it.hasGeneratedContent() }
            ) {
                ProbeOutcome.Failed(3, ModelErrorCode.OUTPUT_TOKEN_LIMIT, "connection output budget exhausted", false)
            } else if (events.lastOrNull { it !is ModelEvent.Usage } !is ModelEvent.Completed ||
                events.none { it.hasGeneratedContent() }
            ) {
                ProbeOutcome.Failed(3, ModelErrorCode.PROTOCOL, "connection reply incomplete", false)
            } else {
                connected(previous, catalog)
            }
        return Observation(outcome, true, error?.code == ModelErrorCode.AUTH)
    }

    // Thinking backends may spend this probe's entire token budget on reasoning. This
    // proves connectivity, not visible-answer quality or optional tool capabilities.
    private fun ModelEvent.hasGeneratedContent(): Boolean =
        when (this) {
            is ModelEvent.TextDelta -> text.isNotBlank()
            is ModelEvent.ReasoningDelta -> text.isNotBlank()
            else -> false
        }

    private fun connected(
        previous: ProviderCapabilities?,
        catalog: ModelCatalogResult,
    ): ProbeOutcome =
        ProbeOutcome.Ok(
            previous
                ?: ProviderCapabilities(
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    null,
                    CapabilitySource.CONNECTION_ONLY,
                ),
            (catalog as? ModelCatalogResult.Listed)?.models,
        )
}
