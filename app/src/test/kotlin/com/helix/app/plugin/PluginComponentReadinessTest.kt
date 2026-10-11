package com.helix.app.plugin

import com.helix.extensions.plugin.PluginComponentKind
import com.helix.extensions.plugin.PluginComponentState
import com.helix.extensions.plugin.PluginDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginComponentReadinessTest {
    @Test fun skippedServerCannotMakeTheRemainingSkillLookFullyReady() {
        val state = snapshot(true, listOf(true), listOf("PLUGIN_MCP_CONFIG_INVALID"))
        assertEquals(1, state.components.size)
        assertEquals(1, state.ready)
        assertTrue(state.hasSkippedComponents)
        assertFalse(state.fullyReady)
    }

    @Test fun ignoredMetadataDoesNotDisableWorkingComponents() {
        val state = snapshot(true, listOf(true), listOf("PLUGIN_UNKNOWN_FIELD:future"))
        assertFalse(state.hasSkippedComponents)
        assertTrue(state.fullyReady)
    }

    @Test fun emptyPackageDoesNotPretendToBeReady() {
        val state = snapshot(true, emptyList())
        assertEquals(0, state.components.size)
        assertFalse(state.fullyReady)
    }

    @Test fun oneWorkingSkillDoesNotHideAnOfflineServer() {
        val state = snapshot(true, listOf(true, false))
        assertEquals(1, state.ready)
        assertEquals(2, state.components.size)
        assertFalse(state.fullyReady)
    }

    @Test fun disabledPackageKeepsComponentsButIsNotReady() {
        val state = snapshot(false, listOf(true, true))
        assertEquals(0, state.ready)
        assertEquals(2, state.components.size)
        assertFalse(state.fullyReady)
    }

    @Test fun allWorkingComponentsAreReadyWithoutGrantingSessionSelection() {
        val state = snapshot(true, listOf(true, true, true))
        assertEquals(3, state.ready)
        assertTrue(state.fullyReady)
    }

    private fun snapshot(
        enabled: Boolean,
        facts: List<Boolean>,
        diagnostics: List<String> = emptyList(),
    ) = PluginReadinessSnapshot(
        "owner",
        1,
        false,
        enabled,
        facts.mapIndexed { index, ready ->
            PluginComponentFact(
                "component-$index",
                PluginComponentKind.SKILL,
                if (ready) PluginComponentState.AVAILABLE else PluginComponentState.NOT_CONNECTED,
                true,
                ready,
            )
        },
        diagnostics.map(PluginDiagnostics::project),
    )
}
