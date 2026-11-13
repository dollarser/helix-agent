package com.helix.app.provider

import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ProviderTransport
import com.helix.core.model.ReasoningEffort
import com.helix.provider.api.ModelMetadata

/** Reviewed API contracts, not prefix matching or evidence that a proxy implements OpenAI. */
internal object OpenAiReasoningMetadata {
    // Source: https://developers.openai.com/api/docs/models/{exact model}, reviewed 2026-11-12.
    // OFF means omit the parameter; NONE is the distinct wire value "none".
    private val efforts =
        buildMap {
            fun add(
                ids: List<String>,
                values: List<String>,
            ) {
                ids.forEach { put(it, values.map(ReasoningEffort::fromWire)) }
            }
            add(listOf("gpt-4.1", "gpt-4.1-2025-04-14"), emptyList())
            add(listOf("gpt-5", "gpt-5-2025-08-07"), listOf("minimal", "low", "medium", "high"))
            add(listOf("gpt-5.1", "gpt-5.1-2025-11-13"), listOf("none", "low", "medium", "high"))
            add(listOf("gpt-5.2", "gpt-5.2-2025-12-11"), listOf("none", "low", "medium", "high", "xhigh"))
            add(listOf("gpt-5.4", "gpt-5.4-2026-03-05"), listOf("none", "low", "medium", "high", "xhigh"))
            add(listOf("gpt-6-astra"), listOf("low", "medium", "high", "xhigh", "max"))
        }

    @Suppress("ReturnCount") // Reject untrusted transport and endpoint before exact contract lookup.
    fun forModel(
        transport: ProviderTransport,
        model: String,
    ): ModelMetadata? {
        val network = transport as? ProviderTransport.Network ?: return null
        val endpoint = network.endpoint
        if (network.protocol !in setOf(ProviderProtocol.OPENAI_RESPONSES, ProviderProtocol.OPENAI_CHAT_COMPLETIONS) ||
            endpoint.origin != "https://api.openai.com:443" ||
            endpoint.path.trimEnd('/') != "/v1"
        ) {
            return null
        }
        return efforts[model]?.let { ModelMetadata(reasoningEfforts = it) }
    }
}
