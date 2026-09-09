package com.helix.app.provider

import com.helix.app.internal.InMemoryLineStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProviderContextSettingsTest {
    @Test fun defaultsAndServerWindowAreSeparateFromTurnBudgets() {
        assertEquals(200_000L, ProviderContextSettings().window)
        assertEquals(80, ProviderContextSettings().triggerPercent)
        assertEquals(262144L, ProviderContextSettings(serverWindow = 262144).window)
        assertEquals(100000L, ProviderContextSettings(manualWindow = 100000, serverWindow = 262144).window)
        assertEquals(8192L, ProviderContextSettings(manualWindow = 200000, serverWindow = 8192).window)
    }

    @Test fun persistedPolicyDoesNotLeakAcrossModelEndpointOrProvider() {
        val memory = InMemoryLineStore()
        val store = ProviderContextSettingsStore(memory)
        val config = ProviderContextSettings(32000, 65536, false, 65)
        store.write("p", "https://example.test/v1", "model-a", config)
        assertEquals(config, ProviderContextSettingsStore(memory).read("p", "https://example.test/v1", "model-a"))
        assertEquals(ProviderContextSettings(), store.read("p", "https://example.test/v1", "model-b"))
        assertEquals(ProviderContextSettings(), store.read("p", "https://other.test/v1", "model-a"))
        assertEquals(ProviderContextSettings(), store.read("q", "https://example.test/v1", "model-a"))
    }

    @Test fun invalidWindowAndThresholdAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { ProviderContextSettings(manualWindow = 0) }
        assertThrows(IllegalArgumentException::class.java) { ProviderContextSettings(serverWindow = 1000001) }
        assertThrows(IllegalArgumentException::class.java) { ProviderContextSettings(triggerPercent = 100) }
        assertThrows(IllegalArgumentException::class.java) { ProviderContextSettings(triggerPercent = -1) }
    }
}
