package com.helix.app.provider

import com.helix.core.model.ReasoningEffort
import com.helix.provider.api.ProbeOutcome

/** Explicit picker action: prefer model metadata, then reuse identity-checked capability detection. */
suspend fun ProviderService.discoverReasoningOptions(
    providerId: String,
    model: String,
): List<ReasoningEffort> {
    if (BuiltInModel.owns(providerId)) {
        builtInModels.refresh(false)
        return BuiltInModel.reasoningOptionsFor(model)
    }
    refreshModelCatalog(providerId)
    val declared =
        rows.value
            .firstOrNull { it.id == providerId }
            ?.modelMetadata
            ?.get(model)
            ?.reasoningEfforts
    if (declared == null) {
        check(runCapabilityTest(providerId, model) is ProbeOutcome.Ok) { "CAPABILITY_DETECTION_FAILED" }
    }
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
        explicit != null -> emptyList()
        capabilitiesForModel(selectedModel)?.reasoning == true -> ReasoningEffort.FALLBACK
        else -> emptyList()
    }
}
