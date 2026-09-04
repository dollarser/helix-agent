package com.helix.app.provider

import com.helix.app.internal.InMemoryLineStore
import com.helix.core.model.ModelErrorCode
import com.helix.provider.api.CapabilitySource
import com.helix.provider.api.CleartextAuthorization
import com.helix.provider.api.ProviderCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderStoresTest {
    private val capabilities =
        ProviderCapabilities(
            streaming = true,
            toolCalls = true,
            parallelToolCalls = false,
            vision = false,
            reasoning = false,
            jsonSchemaOutput = true,
            maxContextTokens = 32_000L,
            source = CapabilitySource.PROBED,
        )

    @Test
    fun testStatusDefaultsToUntested() {
        val store = ProviderTestStatusStore(InMemoryLineStore())
        assertEquals(ConnectionTestStatus.Untested, store.statusFor("prov_1"))
    }

    @Test
    fun passedStatusRoundTripsWithCapabilities() {
        val backing = InMemoryLineStore()
        val store = ProviderTestStatusStore(backing)
        store.recordPassed("prov_1", 1_000L, capabilities)
        val status = store.statusFor("prov_1") as ConnectionTestStatus.Passed
        assertEquals(1_000L, status.atMillis)
        assertEquals(capabilities, status.capabilities)
        // Reload from the same backing (restart simulation).
        val reloaded = ProviderTestStatusStore(backing).statusFor("prov_1") as ConnectionTestStatus.Passed
        assertEquals(capabilities, reloaded.capabilities)
    }

    @Test
    fun failedStatusRoundTripsWithPhaseAndCode() {
        val store = ProviderTestStatusStore(InMemoryLineStore())
        store.recordFailed("prov_1", 2_000L, phase = 1, code = ModelErrorCode.AUTH, retryable = false)
        val status = store.statusFor("prov_1") as ConnectionTestStatus.Failed
        assertEquals(1, status.phase)
        assertEquals(ModelErrorCode.AUTH, status.code)
        assertEquals(false, status.retryable)
    }

    @Test
    fun corruptRowDegradesToUntestedNeverPassed() {
        val backing = InMemoryLineStore()
        backing.setLines("provider_test_status", listOf("prov_1|GARBAGE|notanumber|xx|xx|xx|xx|xx"))
        assertEquals(ConnectionTestStatus.Untested, ProviderTestStatusStore(backing).statusFor("prov_1"))
    }

    @Test
    fun clearRemovesTheRecord() {
        val backing = InMemoryLineStore()
        val store = ProviderTestStatusStore(backing)
        store.recordPassed("prov_1", 1L, capabilities)
        store.clear("prov_1")
        assertEquals(ConnectionTestStatus.Untested, ProviderTestStatusStore(backing).statusFor("prov_1"))
    }

    @Test
    fun cleartextBindingsAuthorizeTheExactHostPortOnly() {
        val backing = InMemoryLineStore()
        val store = CleartextBindingStore(backing)
        store.authorize(CleartextAuthorization("192.168.1.20", 11434))
        store.authorize(CleartextAuthorization("192.168.1.20", 11434)) // idempotent
        assertEquals(1, store.all().size)
        assertEquals(
            CleartextAuthorization.isPermitted(
                com.helix.core.model.NormalizedEndpoint
                    .parse("http://192.168.1.20:11434/v1"),
                store.all(),
            ),
            true,
        )
        assertEquals(
            CleartextAuthorization.isPermitted(
                com.helix.core.model.NormalizedEndpoint
                    .parse("http://192.168.1.20:9999/v1"),
                store.all(),
            ),
            false,
        )
    }

    @Test
    fun cleartextBindingOnTheDefaultHttpPortRoundTrips() {
        // Regression: encode() used to omit port 80 (a bare host line), which all() then
        // dropped — so a confirmed authorization for an http endpoint on the default port
        // was recorded yet never read back, and the send gate always blocked it.
        val backing = InMemoryLineStore()
        val store = CleartextBindingStore(backing)
        val auth = CleartextAuthorization("192.168.1.50", 80)
        store.authorize(auth)
        assertEquals("the port-80 binding must survive the round trip", 1, store.all().size)
        assertTrue(auth in store.all())
        assertEquals(
            CleartextAuthorization.isPermitted(
                com.helix.core.model.NormalizedEndpoint
                    .parse("http://192.168.1.50/v1"),
                store.all(),
            ),
            true,
        )
        // A restart sees the same binding (the persisted line is re-read).
        assertTrue(CleartextBindingStore(backing).all().contains(auth))
    }

    @Test
    fun pruneToRevokesUnreferencedBindings() {
        val backing = InMemoryLineStore()
        val store = CleartextBindingStore(backing)
        val a = CleartextAuthorization("10.0.0.5", 8000)
        val b = CleartextAuthorization("10.0.0.6", 8000)
        store.authorize(a)
        store.authorize(b)
        store.pruneTo(setOf(a))
        val all = store.all()
        assertTrue(a in all)
        assertTrue(b !in all)
        // A restart sees only the surviving binding.
        assertTrue(CleartextBindingStore(backing).all().contains(a))
    }
}
