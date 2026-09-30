package com.helix.app.provider

import com.helix.provider.api.CapabilitySource
import com.helix.provider.api.ProviderCapabilities
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderProbePublicationTest {
    @Test fun slowConnectionProbeCannotEraseNewerCapabilityEvidence() {
        val connectivity =
            ProviderCapabilities(
                streaming = true,
                toolCalls = false,
                parallelToolCalls = false,
                vision = false,
                reasoning = false,
                jsonSchemaOutput = false,
                maxContextTokens = null,
                source = CapabilitySource.CONNECTION_ONLY,
            )
        val detected = connectivity.copy(vision = true, toolCalls = true, source = CapabilitySource.PROBED)
        assertEquals(detected, ProviderProbePublication.capabilities(false, connectivity, detected))
        assertEquals(connectivity, ProviderProbePublication.capabilities(true, connectivity, detected))
    }
}
