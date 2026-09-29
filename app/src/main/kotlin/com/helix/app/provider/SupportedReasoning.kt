package com.helix.app.provider

import com.helix.core.model.ReasoningEffort

internal fun supportedReasoning(
    preferred: ReasoningEffort,
    supported: List<ReasoningEffort>,
): ReasoningEffort = preferred.takeIf { it in supported } ?: ReasoningEffort.OFF

/** Shared by request assembly and compaction; a default-model probe proves no other model. */
internal fun ProviderRowUi.reasoningOptionsFor(selectedModel: String): List<ReasoningEffort> {
    if (!chatSelectable) return emptyList()
    val explicit = modelMetadata[selectedModel]?.reasoningEfforts
    return when {
        explicit != null && explicit.isNotEmpty() -> listOf(ReasoningEffort.OFF) + explicit
        explicit != null -> emptyList()
        selectedModel == model && capabilities?.reasoning == true -> ReasoningEffort.FALLBACK
        else -> emptyList()
    }
}
