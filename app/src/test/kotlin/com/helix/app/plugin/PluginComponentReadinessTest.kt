package com.helix.app.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginComponentReadinessTest {
    @Test fun skippedServerCannotMakeTheRemainingSkillLookFullyReady() {
        val state = PluginComponentReadiness(true, listOf(true), listOf("PLUGIN_MCP_CONFIG_INVALID"))
        assertEquals(1, state.total)
        assertEquals(1, state.ready)
        assertTrue(state.hasSkippedComponents)
        assertFalse(state.fullyReady)
    }

    @Test fun ignoredMetadataDoesNotDisableWorkingComponents() {
        val state = PluginComponentReadiness(true, listOf(true), listOf("PLUGIN_UNKNOWN_FIELD:future"))
        assertFalse(state.hasSkippedComponents)
        assertTrue(state.fullyReady)
    }

    @Test fun emptyPackageDoesNotPretendToBeReady() {
        val state = PluginComponentReadiness(true, emptyList())
        assertEquals(0, state.total)
        assertFalse(state.fullyReady)
    }

    @Test fun oneWorkingSkillDoesNotHideAnOfflineServer() {
        val state = PluginComponentReadiness(true, listOf(true, false))
        assertEquals(1, state.ready)
        assertEquals(2, state.total)
        assertFalse(state.fullyReady)
    }

    @Test fun disabledPackageKeepsComponentsButIsNotReady() {
        val state = PluginComponentReadiness(false, listOf(true, true))
        assertEquals(0, state.ready)
        assertEquals(2, state.total)
        assertFalse(state.fullyReady)
    }

    @Test fun allWorkingComponentsAreReadyWithoutGrantingSessionSelection() {
        val state = PluginComponentReadiness(true, listOf(true, true, true))
        assertEquals(3, state.ready)
        assertTrue(state.fullyReady)
    }
}
