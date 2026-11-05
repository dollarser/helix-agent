package com.helix.app.connector

import com.helix.app.R
import com.helix.app.plugin.PluginSessionRow
import com.helix.app.plugin.PluginSetupReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PluginSessionStateTest {
    private val ready = PluginSessionRow("plugin", "Mobile Use", false, false, true, true, readyComponents = 1)

    @Test fun primaryPluginsSortFirstWithoutPromotingNamesOrReorderingOtherEntries() {
        val rows =
            listOf("z", "bundled-plugin:mobile-use", "a", "bundled-plugin:browser", "browser").map {
                ready.copy(id = it)
            }
        assertEquals(
            listOf("bundled-plugin:browser", "bundled-plugin:mobile-use", "z", "a", "browser"),
            rows.sortedBy(::pluginSelectionPriority).map { it.id },
        )
    }

    @Test fun registeredToolsDoNotHideUnreadableConfiguration() {
        assertEquals(
            R.string.mobile_use_configuration_error,
            selectionIssueLabel(ready.copy(selected = true, setupReason = PluginSetupReason.MOBILE_CONFIGURATION)),
        )
        assertNull(selectionIssueLabel(ready.copy(setupReason = PluginSetupReason.RELOAD)))
        assertEquals(
            R.string.mobile_use_needs_access,
            selectionIssueLabel(ready.copy(selected = true, setupReason = PluginSetupReason.MOBILE_BACKEND)),
        )
        assertNull(selectionIssueLabel(ready.copy(selected = true)))
        assertNull(selectionIssueLabel(ready.copy(selected = true, ready = false, totalComponents = 2)))
        assertNull(
            selectionIssueLabel(
                ready.copy(
                    selected = true,
                    ready = false,
                    readyComponents = 0,
                    setupReason = PluginSetupReason.HOST_UNAVAILABLE,
                ),
            ),
        )
        assertNull(
            selectionIssueLabel(
                ready.copy(
                    selected = true,
                    ready = false,
                    readyComponents = 0,
                    setupReason = PluginSetupReason.CONNECTION,
                ),
            ),
        )
    }

    @Test fun unavailableAndDisabledRemainExplicit() {
        assertNull(selectionIssueLabel(ready.copy(available = false)))
        assertNull(selectionIssueLabel(ready.copy(enabled = false)))
        assertEquals(
            R.string.connector_session_unavailable,
            selectionIssueLabel(ready.copy(selected = true, available = false)),
        )
        assertEquals(R.string.connector_inactive, selectionIssueLabel(ready.copy(selected = true, enabled = false)))
    }
}
