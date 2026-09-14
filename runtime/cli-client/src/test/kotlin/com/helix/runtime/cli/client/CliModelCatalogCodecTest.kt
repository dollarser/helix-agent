package com.helix.runtime.cli.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CliModelCatalogCodecTest {
    @Test fun futureEffortsAndUnknownCapabilitiesCrossIpcWithoutACompiledModelRoster() {
        val catalog =
            CliModelCatalog.Listed(
                listOf(
                    CliModelInfo("future/model", true, listOf("low", "adaptive_next"), 234567),
                    CliModelInfo("unknown", null, null, null),
                    CliModelInfo("unsupported", false, emptyList(), null),
                ),
            )
        assertEquals(catalog, CliModelCatalogCodec.decode(CliModelCatalogCodec.encode(catalog)))
    }

    @Test fun malformedEffortsAndOversizedWindowsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { CliModelInfo("x", true, listOf("bad token"), null) }
        assertThrows(IllegalArgumentException::class.java) { CliModelInfo("x", true, listOf("off"), null) }
        assertThrows(IllegalArgumentException::class.java) { CliModelInfo("x", true, null, 10_000_001) }
    }
}
