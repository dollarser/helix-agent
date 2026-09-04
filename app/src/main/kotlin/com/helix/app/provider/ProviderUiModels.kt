package com.helix.app.provider

import com.helix.app.R
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ProviderResidence
import com.helix.core.storage.entity.ProviderConfigEntity
import com.helix.provider.api.ProviderCapabilities
import com.helix.provider.api.ProviderConfig

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
    /**
     * The backend model list carried out of the last PASSED connection test
     * (HXA-059, from [ConnectionTestStatus.Passed.modelIds]); null when there
     * is no list (untested/failed/unsupported). Display data only — the row's
     * [model] stays the persisted (user-chosen) model.
     */
    val backendModels: List<String>?,
    val templateNotes: List<String>,
) {
    /**
     * Selectable for a new session only when the connection test COMPLETED
     * (HXA-028: "未完成连接测试不贬为'已可用'").
     */
    val chatSelectable: Boolean
        get() = status is ConnectionTestStatus.Passed

    /**
     * Capability chips for the UI (doc 10 section 2.4: rely on capability tests). HXA-069: each
     * chip is a STABLE string-resource id + args (this mapper is not a composable and must hold
     * no locale text) — the @Composable that renders the chips resolves them via `stringResource`.
     */
    val capabilityChips: List<CapabilityChip> =
        capabilities
            ?.let { caps ->
                buildList {
                    add(CapabilityChip(R.string.provider_capability_streaming, listOf(mark(caps.streaming))))
                    add(CapabilityChip(R.string.provider_capability_tool_calls, listOf(mark(caps.toolCalls))))
                    add(CapabilityChip(R.string.provider_capability_vision, listOf(mark(caps.vision))))
                    add(CapabilityChip(R.string.provider_capability_reasoning, listOf(mark(caps.reasoning))))
                    add(CapabilityChip(R.string.provider_capability_json, listOf(mark(caps.jsonSchemaOutput))))
                    caps.maxContextTokens?.let { tokens ->
                        add(CapabilityChip(R.string.provider_capability_context, listOf((tokens / 1000).toString())))
                    }
                    if (caps.source == com.helix.provider.api.CapabilitySource.MANUAL) {
                        add(CapabilityChip(R.string.provider_capability_manual))
                    }
                }
            }.orEmpty()

    private fun mark(value: Boolean): String = if (value) " ✓" else " ✗"
}

/**
 * One capability chip as a stable string-resource id + args (HXA-069: emitted by the pure
 * mapper, resolved to the current locale by the @Composable that renders it).
 */
data class CapabilityChip(
    val res: Int,
    val args: List<String> = emptyList(),
)

/** A capability chip set for the chat header (from the session's provider). */
data class ProviderBadgeUi(
    val displayName: String,
    val model: String,
    val origin: String,
    val residence: ProviderResidence,
    val chips: List<CapabilityChip>,
)

/**
 * One persisted provider as its UI row (HXA-028; pure, unit-tested): a corrupt
 * row throws IAE (fail closed — the caller hides the row and the provider
 * stays non-selectable). Since HXA-059 the row also carries the backend model
 * list when the test passed with one (null otherwise).
 */
internal fun providerRowUi(
    entity: ProviderConfigEntity,
    status: ConnectionTestStatus,
): ProviderRowUi {
    val config =
        ProviderConfig.fromStorage(
            entity.id,
            entity.displayName,
            entity.protocol,
            entity.endpoint,
            entity.model,
            entity.headersJson,
            entity.secretAlias,
            entity.capabilitySnapshot,
        )
    return ProviderRowUi(
        id = entity.id,
        displayName = entity.displayName,
        protocol = config.protocol,
        origin = config.endpoint.origin,
        residence = config.residence(),
        model = entity.model,
        hasKey = entity.secretAlias != ProviderFactory.NO_KEY_ALIAS,
        isCleartext = config.endpoint.scheme == "http",
        status = status,
        capabilities = (status as? ConnectionTestStatus.Passed)?.capabilities,
        backendModels = (status as? ConnectionTestStatus.Passed)?.modelIds,
        templateNotes = emptyList(),
    )
}
