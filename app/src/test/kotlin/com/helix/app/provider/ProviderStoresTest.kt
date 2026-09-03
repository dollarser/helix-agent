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
        assertEquals(ConnectionTestMapping.codeLabel(ModelErrorCode.AUTH), status.codeLabel)
        assertEquals(false, status.retryable)
        assertTrue(status.chipText().contains("网络与认证"))
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

    // --- HXA-059: the optional 8th field (backend model list) ---

    @Test
    fun passedWithModelListRoundTripsThroughTheEighthField() {
        val backing = InMemoryLineStore()
        val store = ProviderTestStatusStore(backing)
        val ids = listOf("fixture-model-a", "path/with/separators-b", "c")
        store.recordPassed("prov_1", 5_000L, capabilities, ids)
        // The line carries 8 fields (the model list JSON array).
        val line = backing.lines("provider_test_status").single()
        assertEquals(8, line.split("|", limit = 8).size)
        val status = store.statusFor("prov_1") as ConnectionTestStatus.Passed
        assertEquals(ids, status.modelIds)
        // Restart simulation: the same backing is re-read with the list intact.
        val reloaded = ProviderTestStatusStore(backing).statusFor("prov_1") as ConnectionTestStatus.Passed
        assertEquals(ids, reloaded.modelIds)
    }

    @Test
    fun passedWithoutModelListWritesTheSevenFieldLine() {
        val backing = InMemoryLineStore()
        val store = ProviderTestStatusStore(backing)
        store.recordPassed("prov_1", 5_000L, capabilities, null)
        val line = backing.lines("provider_test_status").single()
        // No 8th field: a pre-HXA-059 reader (split limit 7) sees a valid row.
        assertEquals(7, line.split("|").size)
        assertEquals(null, (store.statusFor("prov_1") as ConnectionTestStatus.Passed).modelIds)
        store.recordPassed("prov_1", 5_000L, capabilities, emptyList())
        assertEquals(
            7,
            backing
                .lines("provider_test_status")
                .single()
                .split("|")
                .size,
        )
        assertEquals(null, (store.statusFor("prov_1") as ConnectionTestStatus.Passed).modelIds)
    }

    @Test
    fun aSevenFieldLegacyLineReadsBackAsNoList() {
        // A row written before HXA-059 (no model list field) stays valid and
        // reads back as PASSED with modelIds = null.
        val backing = InMemoryLineStore()
        backing.setLines(
            "provider_test_status",
            listOf(
                "prov_1|PASSED|9000|0|-|false|" +
                    com.helix.provider.api.ProviderCapabilities
                        .toJsonString(capabilities),
            ),
        )
        val status = ProviderTestStatusStore(backing).statusFor("prov_1") as ConnectionTestStatus.Passed
        assertEquals(capabilities, status.capabilities)
        assertEquals(null, status.modelIds)
    }

    @Test
    fun aCorruptEighthFieldDegradesTheListOnlyKeepingPassed() {
        val backing = InMemoryLineStore()
        val validCaps =
            com.helix.provider.api.ProviderCapabilities
                .toJsonString(capabilities)
        for (corrupt in listOf("not-json", "42", "{\"object\":true}", "[\"ok\", 5]", "[\"a\",")) {
            backing.setLines(
                "provider_test_status",
                listOf("prov_1|PASSED|9000|0|-|false|$validCaps|$corrupt"),
            )
            val status = ProviderTestStatusStore(backing).statusFor("prov_1")
            // The list is display data: losing it must NOT pretend the provider
            // is untested — the PASSED status (and its capabilities) is kept.
            assertEquals("corrupt field: $corrupt", ConnectionTestStatus.Passed::class, status::class)
            assertEquals("corrupt field: $corrupt", capabilities, (status as ConnectionTestStatus.Passed).capabilities)
            assertEquals("corrupt field: $corrupt", null, status.modelIds)
        }
    }

    @Test
    fun aModelIdWithAPipeSurvivesTheEighthField() {
        // split("|", limit = 8) keeps the remainder in field 8: a `|` inside a
        // model id does not corrupt the list.
        val backing = InMemoryLineStore()
        val store = ProviderTestStatusStore(backing)
        val ids = listOf("model|with|pipe", "plain")
        store.recordPassed("prov_1", 5_000L, capabilities, ids)
        assertEquals(ids, (store.statusFor("prov_1") as ConnectionTestStatus.Passed).modelIds)
    }

    @Test
    fun aStoredModelListIsReboundedOnRead() {
        // A hand-tampered file must not grow the UI list without bound: field 8
        // is re-run through the probe's normalization (bound 1000) on read.
        val backing = InMemoryLineStore()
        val validCaps =
            com.helix.provider.api.ProviderCapabilities
                .toJsonString(capabilities)
        val big = (1..1_500).joinToString(",", prefix = "[", postfix = "]") { "\"m$it\"" }
        backing.setLines(
            "provider_test_status",
            listOf("prov_1|PASSED|9000|0|-|false|$validCaps|$big"),
        )
        val status = ProviderTestStatusStore(backing).statusFor("prov_1") as ConnectionTestStatus.Passed
        assertEquals(1_000, status.modelIds!!.size)
    }

    @Test
    fun recordFailedAndClearDropTheModelList() {
        val backing = InMemoryLineStore()
        val store = ProviderTestStatusStore(backing)
        store.recordPassed("prov_1", 1L, capabilities, listOf("m1"))
        store.recordFailed("prov_1", 2L, phase = 2, code = ModelErrorCode.AUTH, retryable = false)
        assertEquals(ConnectionTestStatus.Failed::class, store.statusFor("prov_1")::class)
        // The FAILED line never carries a list (7 fields).
        assertEquals(
            7,
            backing
                .lines("provider_test_status")
                .single()
                .split("|")
                .size,
        )
        // clear removes the whole line (status + list).
        store.clear("prov_1")
        assertEquals(ConnectionTestStatus.Untested, store.statusFor("prov_1"))
        assertTrue(backing.lines("provider_test_status").isEmpty())
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
