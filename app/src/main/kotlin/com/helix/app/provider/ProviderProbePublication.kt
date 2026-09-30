package com.helix.app.provider

import com.helix.provider.api.CapabilitySource
import com.helix.provider.api.ProviderCapabilities

/** Connectivity does not overwrite newer capability evidence from a different probe lane. */
internal object ProviderProbePublication {
    fun capabilities(
        detectCapabilities: Boolean,
        observed: ProviderCapabilities,
        latest: ProviderCapabilities,
    ): ProviderCapabilities =
        if (!detectCapabilities && latest.source != CapabilitySource.CONNECTION_ONLY) latest else observed
}
