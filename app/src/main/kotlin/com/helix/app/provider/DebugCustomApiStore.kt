package com.helix.app.provider

import com.helix.app.internal.LineStore
import com.helix.core.model.ProviderTransport
import com.helix.provider.api.ProviderConfig

/** Development-only eligibility, recorded from the explicit form choice; never provider authority. */
class DebugCustomApiStore(
    private val store: LineStore,
    private val debuggable: Boolean = false,
) {
    fun record(
        providerId: String,
        draft: ProviderDraft,
    ) {
        if (!debuggable) return
        val binding = ProviderTransport.Network(draft.protocol, draft.endpoint).cacheKey
        store.setLines(key(providerId), if (draft.templateId == "generic-openai") listOf(binding) else emptyList())
    }

    fun enabled(config: ProviderConfig): Boolean =
        debuggable && store.lines(key(config.id)) == listOf(config.transport.cacheKey)

    fun clear(providerId: String) = store.setLines(key(providerId), emptyList())

    private fun key(providerId: String): String = "debug-custom-api-$providerId"
}
