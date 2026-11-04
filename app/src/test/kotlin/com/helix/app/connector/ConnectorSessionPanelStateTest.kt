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
    private val row = PluginSessionRow("plugin", "Plugin", false, false, true, true, readyComponents = 1)

    @Test fun applyTimeSetupFailureTargetsTheActualPluginAndClearsOnlyItsNewCheck() =
        runBlocking {
            val state =
                ConnectorSessionPanelState(
                    { "A" to listOf(row, row.copy(id = "second")) },
                    { _, _ ->
                        throw com.helix.app.plugin.PluginSelectionUnavailable(
                            com.helix.app.plugin.PluginSetupReason.CONNECTION,
                            "second",
                        )
                    },
                ) { true }
            state.refresh()
            state.rows.forEach { state.select(it, true) }
            assertFalse(state.apply())
            assertEquals("second", state.setupRow?.id)
            assertTrue(state.rows.first().selected)
            assertFalse(state.rows.last().selected)
        }

    @Test fun repairReturnSelectsOnlyReadyOriginalPluginAndPreservesOtherDrafts() =
        runBlocking {
            val blocked =
                row.copy(
                    ready = false,
                    readyComponents = 0,
                    setupReason = com.helix.app.plugin.PluginSetupReason.CONNECTION,
                )
            var facts = listOf(blocked, row.copy(id = "other"))
            var writes = 0
            val state = ConnectorSessionPanelState({ "A" to facts }, { _, _ -> writes++ }) { true }
            state.refresh()
            state.select(state.rows[1], true)
            state.select(state.rows[0], true)
            assertFalse(state.rows[0].selected)
            assertEquals("plugin", state.setupRow?.id)
            state.beginRepair()
            facts = listOf(row, row.copy(id = "other"))
            state.refresh(afterConfiguration = true)
            assertTrue(state.rows.all { it.selected })
            assertEquals(0, writes)
            assertTrue(state.apply())
            assertEquals(1, writes)
        }

    @Test fun incompleteRepairAndSessionChangeNeverSelectOrWaitForUnrelatedLaterReadiness() =
        runBlocking {
            var current = true
            var facts = row.copy(ready = false, readyComponents = 0)
            val state =
                ConnectorSessionPanelState({ "A" to listOf(facts) }, { _, _ -> error("unexpected write") }) { current }
            state.refresh()
            state.select(state.rows.single(), true)
            state.beginRepair()
            state.refresh(afterConfiguration = true)
            assertFalse(state.rows.single().selected)
            facts = row
            state.refresh()
            assertFalse(state.rows.single().selected)
            facts = row.copy(readyComponents = 0)
            state.refresh()
            state.select(state.rows.single(), true)
            state.beginRepair()
            current = false
            facts = row
            state.refresh(afterConfiguration = true)
            assertFalse(state.rows.single().selected)
        }

    @Test fun savedRepairIntentSurvivesRecreationWithoutGrantingOrLosingOtherDrafts() =
        runBlocking {
            var facts = listOf(row.copy(readyComponents = 0), row.copy(id = "other"))
            val create = { ConnectorSessionPanelState({ "A" to facts }, { _, _ -> error("no apply") }) { true } }
            val state = create()
            state.refresh()
            state.select(state.rows[1], true)
            state.select(state.rows[0], true)
            state.beginRepair()
            val saver = ConnectorSessionPanelState.saver(create)
            val saved =
                with(saver) {
                    androidx.compose.runtime.saveable
                        .SaverScope { true }
                        .save(state)
                }!!
            val restored = saver.restore(saved)!!
            facts = listOf(row, row.copy(id = "other"))
            restored.refresh(afterConfiguration = true)
            assertTrue(restored.rows.all { it.selected })
            assertEquals(null, restored.pendingRepair)
        }

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
