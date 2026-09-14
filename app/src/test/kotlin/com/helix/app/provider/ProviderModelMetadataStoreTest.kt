package com.helix.app.provider

import com.helix.app.internal.InMemoryLineStore
import com.helix.core.model.ReasoningEffort
import com.helix.provider.api.ModelMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProviderModelMetadataStoreTest {
    @Test fun futureEffortsAndMissingFieldsSurviveRestartWithoutLeakingAcrossProviders() {
        val memory = InMemoryLineStore()
        val store = ProviderModelMetadataStore(memory)
        val models =
            mapOf(
                "future-model" to ModelMetadata(listOf(ReasoningEffort.fromWire("adaptive_next")), true, 300001),
                "unknown" to ModelMetadata(),
                "unsupported" to ModelMetadata(emptyList(), false, null),
            )
        store.write("p", "https://fixture.test", models)
        assertEquals(models, ProviderModelMetadataStore(memory).read("p", "https://fixture.test"))
        assertEquals(emptyMap<String, ModelMetadata>(), store.read("q", "https://fixture.test"))
        assertEquals(emptyMap<String, ModelMetadata>(), store.read("p", "https://other.test"))
        assertNotEquals(models["unknown"], models["unsupported"])
        store.write("p", "https://fixture.test", emptyMap())
        assertEquals(emptyMap<String, ModelMetadata>(), store.read("p", "https://fixture.test"))
    }

    @Test fun oldEffortsStayCompatibleAndMalformedServerTokensAreRejected() {
        assertEquals(ReasoningEffort.HIGH, ReasoningEffort.valueOf("HIGH"))
        assertEquals(ReasoningEffort.HIGH, ReasoningEffort.fromWire("high"))
        for (value in listOf("off", "HIGH", "bad token", "x".repeat(33), "x\n")) {
            assertThrows(IllegalArgumentException::class.java) { ReasoningEffort.fromWire(value) }
        }
        assertThrows(IllegalArgumentException::class.java) { ModelMetadata(listOf(ReasoningEffort.OFF)) }
        assertThrows(IllegalArgumentException::class.java) { ModelMetadata(contextWindow = 0) }
    }
}
