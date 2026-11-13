package com.helix.app.provider

import com.helix.app.internal.InMemoryLineStore
import com.helix.core.model.ModelErrorCode
import com.helix.provider.api.CapabilitySource
import com.helix.provider.api.ProviderCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderTestStatusStoreTest {
    private val capabilities =
        ProviderCapabilities(false, false, false, false, false, false, null, CapabilitySource.CONNECTION_ONLY)

    @Test fun emptyDirectoryRoundTripsAsEmptyRatherThanUnknown() {
        val backing = InMemoryLineStore()
        ProviderTestStatusStore(backing).recordPassed("p", 12L, capabilities, emptyList())
        val restored = ProviderTestStatusStore(backing).statusFor("p") as ConnectionTestStatus.Passed
        assertEquals(emptyList<String>(), restored.modelIds)
        assertEquals(capabilities, restored.capabilities)
        assertEquals(12L, restored.atMillis)
    }

    @Test fun unsupportedDirectoryRoundTripsItsSpecificFailureClassification() {
        val backing = InMemoryLineStore()
        ProviderTestStatusStore(backing).recordFailed(
            "p",
            12L,
            2,
            ModelErrorCode.PROTOCOL,
            false,
            catalogUnsupported = true,
        )
        val restored = ProviderTestStatusStore(backing).statusFor("p") as ConnectionTestStatus.Failed
        assertTrue(restored.catalogUnsupported)
        assertEquals(ModelErrorCode.PROTOCOL, restored.code)
        assertEquals(2, restored.phase)
        assertEquals(false, restored.retryable)
    }
}
