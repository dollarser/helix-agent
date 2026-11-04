package com.helix.app.plugin

import org.junit.Assert.assertEquals
import org.junit.Test

class PluginLabelsTest {
    @Test fun displayCasePreservesBrandAcronymsAndLeavesRegistryNamesUntouched() {
        val raw = "mobile-use"
        assertEquals("Mobile Use", pluginTitleCase(raw))
        assertEquals("Browser", pluginTitleCase("browser"))
        assertEquals("MCP Connector", pluginTitleCase("MCP_connector"))
        assertEquals("mobile-use", raw)
    }
}
