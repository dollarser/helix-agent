package com.helix.app.connector

import com.helix.app.plugin.PluginSessionRow
import com.helix.app.plugin.PluginSetupReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginChoiceReadinessTest {
    private val ready = PluginSessionRow("plugin", "Plugin", false, false, true, true, readyComponents = 1)

    @Test fun clickingRechecksLostPermissionAndKeepsTheChoiceUnselected() =
        runBlocking {
            var facts = ready
            var writes = 0
            val state = ConnectorSessionPanelState({ "A" to listOf(facts) }, { _, _ -> writes++ }) { true }
            state.refresh()
            val stale = state.rows.single()
            facts = ready.copy(ready = false, readyComponents = 0, setupReason = PluginSetupReason.MOBILE_BACKEND)
            state.select(stale, true)
            assertFalse(state.rows.single().selected)
            assertEquals(PluginSetupReason.MOBILE_BACKEND, state.setupReason)
            assertEquals("plugin", state.setupRow?.id)
            assertEquals(0, writes)
        }

    @Test fun sessionChangeDuringReadCannotSelectTheOriginalOrAnotherSession() =
        runBlocking {
            var current = true
            var checking = false
            val state =
                ConnectorSessionPanelState({
                    if (checking) current = false
                    "A" to listOf(ready)
                }, { _, _ -> error("No writes expected") }) { current }
            state.refresh()
            checking = true
            state.select(state.rows.single(), true)
            assertFalse(state.rows.single().selected)
            assertFalse(state.busy)
        }

    @Test fun readFailureIsRetryableWithoutOptimisticSelection() =
        runBlocking {
            var fail = false
            val state =
                ConnectorSessionPanelState({
                    check(!fail)
                    "A" to listOf(ready)
                }, { _, _ -> error("No writes expected") }) { true }
            state.refresh()
            fail = true
            state.select(state.rows.single(), true)
            assertTrue(state.failed)
            assertFalse(state.rows.single().selected)
            assertFalse(state.busy)
            fail = false
            state.refresh()
            assertFalse(state.failed)
            assertFalse(state.rows.single().selected)
        }

    @Test fun cancelledCheckReleasesBusyWithoutSelectingOrClaimingFailure() {
        var cancel = false
        val state =
            ConnectorSessionPanelState({
                if (cancel) throw CancellationException()
                "A" to listOf(ready)
            }, { _, _ -> error("No writes expected") }) { true }
        runBlocking { state.refresh() }
        cancel = true
        assertThrows(CancellationException::class.java) { runBlocking { state.select(state.rows.single(), true) } }
        assertFalse(state.rows.single().selected)
        assertFalse(state.busy)
        assertFalse(state.failed)
    }
}
