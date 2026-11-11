package com.helix.provider.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelOutputLimitTest {
    @Test fun onlyExplicitExactModelOutputDeclarationsAreUsed() {
        val result =
            WireModelCatalogParser.parseModelIds(
                """{"data":[
                  {"id":"exact","max_output_tokens":8192,"max_completion_tokens":4096},
                  {"id":"other","max_completion_tokens":1024},
                  {"id":"Qwen3.8-27B","max_model_len":131072,"context_window":131072},
                  {"id":"unknown","max_output_tokens":null}
                ]}""",
            ) as ModelCatalogResult.Listed
        assertEquals(mapOf("exact" to 4096L, "other" to 1024L), result.outputLimits)
        assertEquals(null, ModelMetadata(contextWindow = 131072).maxOutputTokens)
        assertEquals(null, ProviderContextSettings(serverWindow = 131072).serverOutputLimit)
    }

    @Test fun malformedExplicitDeclarationsFailRatherThanBecomingUnknown() {
        for (value in listOf("0", "-1", "10000001", "1.5", "true", "\"8192\"", "{}", "[]")) {
            val result =
                WireModelCatalogParser.parseModelIds(
                    """{"data":[{"id":"exact","max_output_tokens":$value}]}""",
                )
            assertTrue("Invalid declaration: $value", result is ModelCatalogResult.Failed)
        }
    }

    @Test fun metadataAndSettingsRejectInvalidLimitsAndForeignModelKeys() {
        for (value in listOf(0L, -1L, ProviderCapabilities.MAX_CONTEXT_BOUND + 1)) {
            assertThrows(IllegalArgumentException::class.java) { ModelMetadata(maxOutputTokens = value) }
            assertThrows(IllegalArgumentException::class.java) { ProviderContextSettings(serverOutputLimit = value) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ModelCatalogResult.Listed(listOf("exact"), outputLimits = mapOf("different" to 1024L))
        }
        assertEquals(1L, ProviderContextSettings(serverOutputLimit = 1).serverOutputLimit)
    }
}
