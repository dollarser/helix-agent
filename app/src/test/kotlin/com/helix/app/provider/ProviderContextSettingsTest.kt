package com.helix.app.provider

import com.helix.app.internal.InMemoryLineStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProviderContextSettingsTest {
    @Test fun modelMetadataOverridesFallbackAndHonorsUserCap() {
        val defaults = ProviderContextSettings()
        assertEquals(128000L, defaults.withDetectedWindow(128000).window)
        assertEquals(272000L, defaults.withDetectedWindow(272000).window)
        assertEquals(200000L, defaults.withDetectedWindow(null).window)
        assertEquals(64000L, ProviderContextSettings(manualWindow = 64000).withDetectedWindow(128000).window)
        assertEquals(128000L, ProviderContextSettings(serverWindow = 272000).withDetectedWindow(128000).window)
    }

    @Test fun defaultsAndServerWindowAreSeparateFromTurnBudgets() {
        assertEquals(200_000L, ProviderContextSettings().window)
        assertEquals(80, ProviderContextSettings().triggerPercent)
        assertEquals(262144L, ProviderContextSettings(serverWindow = 262144).window)
        assertEquals(100000L, ProviderContextSettings(manualWindow = 100000, serverWindow = 262144).window)
        assertEquals(8192L, ProviderContextSettings(manualWindow = 200000, serverWindow = 8192).window)
        assertEquals(1_050_000L, ProviderContextSettings(serverWindow = 1_050_000).window)
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
        assertThrows(IllegalArgumentException::class.java) {
            ProviderContextSettings(serverWindow = ProviderContextSettings.MAX_WINDOW + 1)
        }
        assertThrows(IllegalArgumentException::class.java) { ProviderContextSettings(triggerPercent = 100) }
        assertThrows(IllegalArgumentException::class.java) { ProviderContextSettings(triggerPercent = -1) }
    }
}
