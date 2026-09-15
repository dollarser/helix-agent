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
import com.helix.provider.api.ProviderCheckResult
import com.helix.provider.api.ProviderConfig
import kotlinx.coroutines.flow.toList

internal object ProviderConnectionCheck {
    suspend fun run(
        config: ProviderConfig,
        provider: ModelProvider,
        previous: ProviderCapabilities?,
    ): ProbeOutcome {
        val subscription = provider as? SubscriptionConnectionProvider
        // Phase 1 (网络与认证) precedes any model traffic: for subscription providers the
        // authenticated account catalog IS that check, for ordinary providers the explicit
        // preflight is (provider doc section 2.4). A phase-1 failure is terminal.
        val accountCatalog = subscription?.connectionCatalog()
        val preflight = if (subscription == null) provider.validateConfiguration() else null
        val catalog = accountCatalog ?: provider.listModels()
        val shortCircuit: ProbeOutcome? =
            when {
                accountCatalog is ModelCatalogResult.Failed -> {
                    ProbeOutcome.Failed(
                        1,
                        accountCatalog.code,
                        accountCatalog.detail,
                        accountCatalog.retryable,
                    )
                }

                preflight is ProviderCheckResult.Failed -> {
                    ProbeOutcome.Failed(
                        1,
                        preflight.code,
                        preflight.detail,
                        preflight.retryable,
                    )
                }

                catalog is ModelCatalogResult.Failed -> {
                    ProbeOutcome.Failed(2, catalog.code, catalog.detail, catalog.retryable)
                }

                accountCatalog is ModelCatalogResult.Listed && accountCatalog.models.isNotEmpty() -> {
                    connected(previous, catalog)
                }

                else -> {
                    null
                }
            }
        if (shortCircuit != null) return shortCircuit
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
        return verifyGeneration(events, previous, catalog)
    }

    private fun verifyGeneration(
        events: List<ModelEvent>,
        previous: ProviderCapabilities?,
        catalog: ModelCatalogResult,
    ): ProbeOutcome {
        val error = events.filterIsInstance<ModelEvent.Error>().firstOrNull()
        // Only ModelEvent.Error is contractually terminal-last (core:model ModelEvent
        // docs); adapters may emit Usage after Completed — real OpenAI sends the usage
        // chunk after finish_reason — so require a Completed with nothing but trailing
        // Usage after it, not Completed as the final event.
        val completedAt = events.indexOfLast { it is ModelEvent.Completed }
        val complete =
            completedAt >= 0 && events.drop(completedAt + 1).all { it is ModelEvent.Usage }
        // A thinking-mode backend may spend the entire 16-token budget on reasoning
        // (finish_reason=length, zero visible text — observed on the SGLang Qwen dev
        // server, HXA-059 device smoke): a ReasoningDelta stream still proves the
        // connection generates; only an output-free stream is protocol-incomplete.
        val generated =
            events.any {
                (it is ModelEvent.TextDelta && it.text.isNotBlank()) ||
                    it is ModelEvent.ReasoningDelta
            }
        return if (error != null) {
            ProbeOutcome.Failed(3, error.code, "connection reply failed", error.retryable)
        } else if (!complete || !generated) {
            ProbeOutcome.Failed(3, ModelErrorCode.PROTOCOL, "connection reply incomplete", false)
        } else {
            connected(previous, catalog)
        }
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
