package com.helix.runtime.cli.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CodexModelCatalogTest {
    @Test fun serverDeclaredFutureModelAndEffortRemainDiscoverable() {
        val catalog =
            CodexModelCatalog.parse(
                """{"models":[
          {"slug":"future","supported_reasoning_levels":[{"effort":"adaptive_next"}],
           "input_modalities":["text","image"],"context_window":300001},
          {"slug":"unknown"},{"slug":"hidden","visibility":"hide"}
        ]}""".toByteArray(),
            )
        assertEquals(listOf("future", "unknown"), catalog.models.map { it.id })
        assertEquals(listOf("adaptive_next"), catalog.models.first().reasoningEfforts)
        assertEquals(true, catalog.models.first().vision)
        assertEquals(300001L, catalog.models.first().contextWindow)
        assertNull(catalog.models.last().reasoningEfforts)
        assertNull(catalog.models.last().vision)
    }

    @Test fun malformedCatalogIsAProtocolArgumentFailure() {
        assertThrows(IllegalArgumentException::class.java) { CodexModelCatalog.parse("{}".toByteArray()) }
    }
}
