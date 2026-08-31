package com.helix.app.provider

import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ProviderResidence
import com.helix.provider.api.ProviderCapabilities

/**
 * The provider row as the UI sees it (HXA-028): everything is derived from
 * persisted state — the [com.helix.core.storage.repository.ProviderConfigRepository]
 * row, the recorded connection-test status and the capability snapshot. The
 * UI never sees entities/DAOs (AGENTS.md) and never sees secrets (NFR-007):
 * only the [hasKey] flag.
 */
data class ProviderRowUi(
    val id: String,
    val displayName: String,
    val protocol: ProviderProtocol,
    val origin: String,
    val residence: ProviderResidence,
    val model: String,
    val hasKey: Boolean,
    val isCleartext: Boolean,
    val status: ConnectionTestStatus,
    /** The PROBED capabilities when the test passed; null otherwise. */
    val capabilities: ProviderCapabilities?,
    val templateNotes: List<String>,
) {
    /**
     * Selectable for a new session only when the connection test COMPLETED
     * (HXA-028: "未完成连接测试不贬为'已可用'").
     */
    val chatSelectable: Boolean
        get() = status is ConnectionTestStatus.Passed

    /** Capability chips for the UI (doc 10 section 2.4: rely on capability tests). */
    val capabilityChips: List<String> =
        capabilities
            ?.let { caps ->
                buildList {
                    add("流式${mark(caps.streaming)}")
                    add("工具调用${mark(caps.toolCalls)}")
                    add("视觉${mark(caps.vision)}")
                    add("推理${mark(caps.reasoning)}")
                    add("JSON${mark(caps.jsonSchemaOutput)}")
                    caps.maxContextTokens?.let { add("上下文 ${it / 1000}K") }
                    if (caps.source == com.helix.provider.api.CapabilitySource.MANUAL) add("手动声明")
                }
            }.orEmpty()

    private fun mark(value: Boolean): String = if (value) " ✓" else " ✗"
}

/** A capability chip set for the chat header (from the session's provider). */
data class ProviderBadgeUi(
    val displayName: String,
    val model: String,
    val origin: String,
    val residence: ProviderResidence,
    val chips: List<String>,
)
