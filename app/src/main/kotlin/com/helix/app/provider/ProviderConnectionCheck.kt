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
    const val CATALOG_UNSUPPORTED = "MODEL_CATALOG_UNSUPPORTED"

    data class Observation(
        val outcome: ProbeOutcome,
        val generationAttempted: Boolean,
        val sourceFailure: Boolean = false,
        val directory: ModelCatalogResult.Listed? = null,
    )

    suspend fun run(
        config: ProviderConfig,
        provider: ModelProvider,
        previous: ProviderCapabilities?,
    ): ProbeOutcome = inspect(config, provider, previous).outcome

    private fun catalogFailure(
        catalog: ModelCatalogResult,
        model: String,
    ): ProbeOutcome.Failed? =
        when (catalog) {
            is ModelCatalogResult.Failed -> {
                ProbeOutcome.Failed(2, catalog.code, catalog.detail, catalog.retryable)
            }

            is ModelCatalogResult.Listed -> {
                when {
                    catalog.models.isEmpty() -> {
                        ProbeOutcome.Failed(2, ModelErrorCode.PROTOCOL, "Model directory is empty", false)
                    }

                    catalog.purposes[model]?.let {
                        it.isNotEmpty() && com.helix.provider.api.ModelPurpose.CHAT !in it
                    } ==
                        true -> {
                        ProbeOutcome.Failed(2, ModelErrorCode.PROTOCOL, "Model is not a conversation model", false)
                    }

                    else -> {
                        null
                    }
                }
            }

            ModelCatalogResult.Unsupported -> {
                null
            }
        }

    private fun sourceCatalogFailure(catalog: ModelCatalogResult): Boolean =
        when (catalog) {
            is ModelCatalogResult.Failed -> true
            is ModelCatalogResult.Listed -> catalog.models.isEmpty()
            ModelCatalogResult.Unsupported -> false
        }

    @Suppress("ReturnCount") // Directory-only checks return before any generation path.
    suspend fun inspect(
        config: ProviderConfig,
        provider: ModelProvider,
        previous: ProviderCapabilities?,
        verifyGeneration: Boolean = false,
    ): Observation {
        if (!verifyGeneration && config.provisioning == com.helix.core.model.ProviderProvisioningKind.USER_CONFIGURED) {
            return inspectDirectory(provider, previous)
        }
        val accountCatalog = (provider as? SubscriptionConnectionProvider)?.connectionCatalog()
        val catalog = accountCatalog ?: provider.listModels()
        val shortCircuit: ProbeOutcome? =
            catalogFailure(catalog, config.model) ?: when {
                !verifyGeneration && accountCatalog is ModelCatalogResult.Listed -> {
                    connected(previous, catalog)
                }

                else -> {
                    null
                }
            }
        if (shortCircuit != null) return Observation(shortCircuit, false, sourceCatalogFailure(catalog))
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

    /** A directory check never selects a model, validates generation, or falls back to a paid request. */
    private suspend fun inspectDirectory(
        provider: ModelProvider,
        previous: ProviderCapabilities?,
    ): Observation =
        when (val catalog = provider.listModels()) {
            is ModelCatalogResult.Listed -> {
                Observation(connected(previous, catalog), false, directory = catalog)
            }

            is ModelCatalogResult.Failed -> {
                Observation(ProbeOutcome.Failed(2, catalog.code, catalog.detail, catalog.retryable), false, true)
            }

            ModelCatalogResult.Unsupported -> {
                Observation(ProbeOutcome.Failed(2, ModelErrorCode.PROTOCOL, CATALOG_UNSUPPORTED, false), false)
            }
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
