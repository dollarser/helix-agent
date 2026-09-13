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
    suspend fun run(
        config: ProviderConfig,
        provider: ModelProvider,
        previous: ProviderCapabilities?,
    ): ProbeOutcome {
        val accountCatalog = (provider as? SubscriptionConnectionProvider)?.connectionCatalog()
        val catalog = accountCatalog ?: provider.listModels()
        if (catalog is ModelCatalogResult.Failed) {
            return ProbeOutcome.Failed(2, catalog.code, catalog.detail, catalog.retryable)
        }
        if (accountCatalog is ModelCatalogResult.Listed && accountCatalog.models.isNotEmpty()) {
            return connected(previous, catalog)
        }
        // Exactly one ordinary, short generation. No tools, images or explicit effort.
        val events =
            provider
                .stream(
                    ModelRequest(
                        model = config.model,
                        messages = listOf(ModelMessage(ModelRole.USER, "Reply only OK.")),
                        maxOutputTokens = 16,
                    ),
                ).toList()
        val error = events.filterIsInstance<ModelEvent.Error>().firstOrNull()
        if (error != null) return ProbeOutcome.Failed(3, error.code, "connection reply failed", error.retryable)
        if (events.lastOrNull() !is ModelEvent.Completed ||
            events.filterIsInstance<ModelEvent.TextDelta>().none { it.text.isNotBlank() }
        ) {
            return ProbeOutcome.Failed(3, ModelErrorCode.PROTOCOL, "connection reply incomplete", false)
        }
        return connected(previous, catalog)
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
