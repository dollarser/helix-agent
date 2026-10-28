package com.helix.app.connector

import com.helix.app.plugin.PluginSessionRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorSessionPanelStateTest {
    private val row = PluginSessionRow("plugin", "Plugin", false, false, true, true)

    @Test fun `selection stays local until explicit apply and recreation reads persisted facts`() =
        runBlocking {
            var persisted = false
            var writes = 0
            val read = { "Original session" to listOf(row.copy(selected = persisted)) }
            val save: (Map<String, Boolean>, Set<String>) -> Unit = { expected, selected ->
                assertEquals(persisted, expected.getValue("plugin"))
                persisted = "plugin" in selected
                writes++
            }
            val cancelled = ConnectorSessionPanelState(read, save) { true }
            cancelled.refresh()
            cancelled.select(cancelled.rows.single(), true)
            assertTrue(cancelled.rows.single().selected)
            assertFalse(persisted)
            assertEquals(0, writes)
            val recreated = ConnectorSessionPanelState(read, save) { true }
            recreated.refresh()
            assertFalse(recreated.rows.single().selected)
            recreated.select(recreated.rows.single(), true)
            assertTrue(recreated.apply())
            assertTrue(persisted)
            assertEquals(1, writes)
        }

    @Test fun `session switch rejects late apply before any write`() =
        runBlocking {
            var current = true
            var writes = 0
            val state = ConnectorSessionPanelState({ "A" to listOf(row) }, { _, _ -> writes++ }) { current }
            state.refresh()
            state.select(row, true)
            current = false
            assertFalse(state.apply())
            assertTrue(state.failed)
            assertEquals(0, writes)
            assertTrue(state.rows.single().selected)
        }

    @Test fun `failed read is not an empty directory and retry replaces stale facts`() =
        runBlocking {
            var fail = true
            var saves = 0
            val state =
                ConnectorSessionPanelState({
                    check(!fail)
                    "A" to listOf(row)
                }, { _, _ -> saves++ }) { true }
            state.refresh()
            assertTrue(state.failed)
            assertFalse(state.loaded)
            assertFalse(state.empty)
            assertFalse(state.editable)
            assertFalse(state.apply())
            assertEquals(0, saves)
            fail = false
            state.refresh()
            assertFalse(state.failed)
            assertEquals(listOf(row), state.rows)
        }

    @Test fun `save failure remains visible and does not report success`() =
        runBlocking {
            val state = ConnectorSessionPanelState({ "A" to listOf(row) }, { _, _ -> error("conflict") }) { true }
            state.refresh()
            state.select(row, true)
            assertFalse(state.apply())
            assertTrue(state.failed)
            assertFalse(state.busy)
        }

    @Test fun `cancellation propagates and releases busy state without claiming success`() {
        val state =
            ConnectorSessionPanelState({ "A" to listOf(row) }, { _, _ -> throw CancellationException() }) { true }
        runBlocking {
            state.refresh()
            state.select(row, true)
        }
        assertThrows(CancellationException::class.java) { runBlocking { state.apply() } }
        assertFalse(state.busy)
        assertFalse(state.failed)
    }
}
