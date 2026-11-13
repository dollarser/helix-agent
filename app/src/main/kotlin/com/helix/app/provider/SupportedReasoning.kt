package com.helix.app.provider

import com.helix.core.model.ReasoningEffort
import com.helix.provider.api.ModelCatalogResult

/** Explicit, identity-checked metadata discovery. Never generates a paid tools/vision fixture. */
suspend fun ProviderService.discoverReasoningOptions(
    providerId: String,
    model: String,
): List<ReasoningEffort> {
    if (BuiltInModel.owns(providerId)) {
        builtInModels.refresh(false)
        return BuiltInModel.reasoningOptionsFor(model)
    }
    check(refreshModelCatalog(providerId) !is ModelCatalogResult.Failed) { "REASONING_DISCOVERY_FAILED" }
    return reasoningOptions(providerId, model)
}

internal fun supportedReasoning(
    preferred: ReasoningEffort,
    supported: List<ReasoningEffort>,
): ReasoningEffort = preferred.takeIf { it in supported } ?: ReasoningEffort.OFF

/** Shared by request assembly and compaction; a default-model probe proves no other model. */
internal fun ProviderRowUi.reasoningOptionsFor(selectedModel: String): List<ReasoningEffort> {
    val explicit = modelMetadata[selectedModel]?.reasoningEfforts
    return when {
        bundled -> BuiltInModel.reasoningOptionsFor(selectedModel)
        !chatSelectable -> emptyList()
        explicit != null && explicit.isNotEmpty() -> listOf(ReasoningEffort.OFF) + explicit
        explicit != null -> listOf(ReasoningEffort.OFF)
        else -> emptyList()
    }
}
