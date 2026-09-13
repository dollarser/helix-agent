package com.helix.provider.api

import com.helix.core.model.ReasoningEffort

/** Exact-model server facts; null means unspecified, an empty effort list means unsupported. */
data class ModelMetadata(
    val reasoningEfforts: List<ReasoningEffort>? = null,
    val vision: Boolean? = null,
    val contextWindow: Long? = null,
) {
    init {
        require(reasoningEfforts == null || reasoningEfforts.size <= 16)
        require(reasoningEfforts == null || reasoningEfforts.distinct().size == reasoningEfforts.size)
        require(reasoningEfforts?.contains(ReasoningEffort.OFF) != true)
        require(contextWindow == null || contextWindow in 1..ProviderCapabilities.MAX_CONTEXT_BOUND)
    }
}
