package com.helix.app.connector

import com.helix.app.R
import com.helix.app.plugin.PluginSessionRow
import org.junit.Assert.assertEquals
import org.junit.Test

class PluginSessionStateTest {
    private val ready = PluginSessionRow("plugin", "Mobile Use", false, false, true, true)

    @Test fun registeredToolsDoNotHideMissingConfiguration() {
        assertEquals(
            R.string.mobile_use_configuration_required,
            statusLabel(ready.copy(selectionError = "MOBILE_USE_NOT_CONFIGURED")),
        )
        assertEquals(R.string.connector_session_setup, statusLabel(ready.copy(selectionError = "UNKNOWN")))
        assertEquals(R.string.connector_session_ready, statusLabel(ready))
    }

    @Test fun unavailableAndDisabledRemainExplicit() {
        assertEquals(R.string.connector_session_unavailable, statusLabel(ready.copy(available = false)))
        assertEquals(R.string.connector_inactive, statusLabel(ready.copy(enabled = false)))
    }
}
