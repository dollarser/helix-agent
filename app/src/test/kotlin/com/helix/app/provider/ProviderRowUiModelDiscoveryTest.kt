package com.helix.app.provider

import com.helix.app.internal.InMemoryLineStore
import com.helix.core.model.ModelErrorCode
import com.helix.core.storage.entity.ProviderConfigEntity
import com.helix.provider.api.CapabilitySource
import com.helix.provider.api.ProviderCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * HXA-059: the backend model list of a PASSED connection-test status flows into
 * the provider row UI, and a failed/untested row carries no list. The row
 * derivation is the pure [providerRowUi] (the same function the service's
 * refresh() maps every persisted row through), so it is exercised here with the
 * persisted status store in between: record → statusFor → row. The full network
 * path (probe → record) is covered by the provider:api probe tests and the
 * device suite (in-APK loopback fixture server).
 */
class ProviderRowUiModelDiscoveryTest {
    private val capabilities =
        ProviderCapabilities(
            streaming = true,
            toolCalls = true,
            parallelToolCalls = false,
            vision = true,
            reasoning = false,
            jsonSchemaOutput = false,
            maxContextTokens = null,
            source = CapabilitySource.PROBED,
        )

    private fun entity() =
        ProviderConfigEntity(
            id = "prov_1",
            displayName = "Fixture",
            protocol = "OPENAI_CHAT_COMPLETIONS",
            endpoint = "https://example.test:443/v1",
            model = "fixture-model-a",
            headersJson = "{}",
            secretAlias = "no-key",
            capabilitySnapshot = ProviderCapabilities.toJsonString(capabilities),
        )

    @Test
    fun passedStatusWithModelListFlowsIntoTheRowUi() {
        val backing = InMemoryLineStore()
        val store = ProviderTestStatusStore(backing)
        val ids = listOf("fixture-model-a", "path/with/separators-b")
        store.recordPassed("prov_1", 1_000L, capabilities, ids)
        val row = providerRowUi(entity(), store.statusFor("prov_1"))
        assertEquals(ids, row.backendModels)
        assertEquals(capabilities, row.capabilities)
        assertEquals(true, row.chatSelectable)
        // The persisted row's own model is untouched by the list (prefill is a
        // form action, not a write).
        assertEquals("fixture-model-a", row.model)
    }

    @Test
    fun passedStatusWithoutModelListHasNullRowBackendModels() {
        val backing = InMemoryLineStore()
        val store = ProviderTestStatusStore(backing)
        store.recordPassed("prov_1", 1_000L, capabilities, null)
        val row = providerRowUi(entity(), store.statusFor("prov_1"))
        assertNull(row.backendModels)
        assertEquals(true, row.chatSelectable)
    }

    @Test
    fun failedStatusRowCarriesNoModelList() {
        val backing = InMemoryLineStore()
        val store = ProviderTestStatusStore(backing)
        store.recordPassed("prov_1", 1_000L, capabilities, listOf("m1"))
        store.recordFailed("prov_1", 2_000L, phase = 2, code = ModelErrorCode.AUTH, retryable = false)
        val row = providerRowUi(entity(), store.statusFor("prov_1"))
        assertNull(row.backendModels)
        assertNull(row.capabilities)
        assertEquals(false, row.chatSelectable)
    }

    @Test
    fun untestedRowCarriesNoModelList() {
        val store = ProviderTestStatusStore(InMemoryLineStore())
        val row = providerRowUi(entity(), store.statusFor("prov_1"))
        assertNull(row.backendModels)
        assertEquals(false, row.chatSelectable)
    }

    @Test
    fun aCorruptModelListDegradesToNullButTheRowStaysPassed() {
        val backing = InMemoryLineStore()
        val caps = ProviderCapabilities.toJsonString(capabilities)
        backing.setLines(
            "provider_test_status",
            listOf("prov_1|PASSED|9000|0|-|false|$caps|not-a-json-array"),
        )
        val store = ProviderTestStatusStore(backing)
        val row = providerRowUi(entity(), store.statusFor("prov_1"))
        assertNull(row.backendModels)
        assertEquals(capabilities, row.capabilities)
        assertEquals(true, row.chatSelectable)
    }
}
