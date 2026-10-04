package com.helix.app.vision

import com.helix.provider.api.CapabilitySource
import com.helix.provider.api.ProbeOutcome
import com.helix.provider.api.ProviderCapabilities
import kotlinx.coroutines.CancellationException

/** Lazily proves optional vision only when a visual tool actually needs pixels. */
internal class VisionCapabilityResolver(
    private val capabilities: suspend (String, String) -> ProviderCapabilities?,
    private val probe: suspend (String, String) -> ProbeOutcome,
) {
    suspend fun available(
        providerId: String,
        modelId: String,
    ): Boolean {
        val current = capabilities(providerId, modelId)
        return when {
            current?.vision == true && current.source != CapabilitySource.CONNECTION_ONLY -> true
            current != null && current.source != CapabilitySource.CONNECTION_ONLY -> false
            else -> probeVision(providerId, modelId)
        }
    }

    private suspend fun probeVision(
        providerId: String,
        modelId: String,
    ): Boolean =
        try {
            val outcome = probe(providerId, modelId)
            outcome is ProbeOutcome.Ok && outcome.capabilities.vision
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
}
