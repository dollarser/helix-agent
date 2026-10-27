package com.helix.app.terminal

import com.helix.app.R
import com.helix.app.internal.InMemoryLineStore
import com.helix.runtime.proot.core.RuntimeExecutionCapacity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalCapacityInteractionTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined + Job())

    @After fun close() = scope.cancel()

    @Test fun fullCapacityKeepsOriginalSelectionConnectionAndNumberingWithoutCreatingBlankPage() {
        val terminal = Terminal(4)
        val model = ManualTerminalViewModel(terminal, scope)
        model.enter(".")
        val before = model.state.value
        val numbering = terminal.store.lines("windows")
        model.open(".")
        val after = model.state.value
        assertTrue(after.capacityRejected)
        assertFalse(after.failed)
        assertEquals(null, after.errorMessage)
        assertEquals(before.activeSessionId, after.activeSessionId)
        assertSame(before.connection, after.connection)
        assertEquals(before.sessions, after.sessions)
        assertEquals(4, terminal.records.size)
        assertEquals(numbering, terminal.store.lines("windows"))
        assertEquals(0, terminal.detachments)
        model.dismissCapacityNotice()
        assertFalse(model.state.value.capacityRejected)
        assertSame(before.connection, model.state.value.connection)
    }

    @Test fun stoppedHistoryDoesNotOccupySlotsAndNewNumberContinuesAfterExit() {
        val terminal = Terminal(4)
        val model = ManualTerminalViewModel(terminal, scope)
        model.enter(".")
        terminal.finish("running-1")
        model.refresh()
        model.open(".")
        assertFalse(model.state.value.capacityRejected)
        assertEquals(
            1L,
            model.state.value.session
                ?.displayNumber,
        )
        assertEquals(5, terminal.records.size)
        assertEquals("STOPPED", terminal.records.getValue("running-1").phase)
        model.open(".")
        assertTrue(model.state.value.capacityRejected)
        assertEquals(
            1L,
            model.state.value.session
                ?.displayNumber,
        )
        assertEquals(5, terminal.records.size)
    }

    @Test fun competingPagesForLastSlotKeepLoserOnOriginalPage() =
        runBlocking {
            val terminal = Terminal(3)
            val first = ManualTerminalViewModel(terminal, scope)
            val second = ManualTerminalViewModel(terminal, scope)
            first.enter(".")
            second.enter(".")
            val original = second.state.value
            val gate = CompletableDeferred<Unit>()
            terminal.acceptGate = gate
            first.open(".")
            assertTrue(first.state.value.busy)
            second.open(".")
            assertTrue(second.state.value.capacityRejected)
            assertSame(original.connection, second.state.value.connection)
            assertEquals(original.activeSessionId, second.state.value.activeSessionId)
            gate.complete(Unit)
            assertEquals(
                4L,
                first.state.value.session
                    ?.displayNumber,
            )
            assertEquals(4, terminal.records.size)
            assertFalse(first.state.value.capacityRejected)
        }

    @Test fun realStartupFailureIsNotMislabeledAsCapacityAndDoesNotReplaceOriginalViewport() {
        val terminal = Terminal(1)
        val model = ManualTerminalViewModel(terminal, scope)
        model.enter(".")
        val original = model.state.value
        terminal.startFailure = "SHELL_START_FAILED"
        model.open(".")
        assertFalse(model.state.value.capacityRejected)
        assertEquals("SHELL_START_FAILED", model.state.value.errorMessage)
        assertSame(original.connection, model.state.value.connection)
        assertEquals(1, terminal.records.size)
        terminal.startFailure = "ENVIRONMENT_BUSY"
        model.open(".")
        assertFalse(model.state.value.capacityRejected)
        assertEquals("ENVIRONMENT_BUSY", model.state.value.errorMessage)
        assertSame(original.connection, model.state.value.connection)
    }

    @Test fun initialEntryPrefersLiveShellOverStoppedHistoryWithoutRequestingAnotherSlot() {
        val terminal = Terminal(4)
        val live = terminal.records.toMap()
        terminal.records.clear()
        terminal.records["history"] = ManualTerminal.State("history", "STOPPED", ".", null, 0, true)
        terminal.records.putAll(live)
        val model = ManualTerminalViewModel(terminal, scope)
        model.enter(".")
        assertEquals("running-1", model.state.value.activeSessionId)
        assertFalse(model.state.value.capacityRejected)
        assertEquals(5, terminal.records.size)
        model.switchSession("history")
        model.enter(".")
        // A stale click cannot reopen a completed terminal or displace the live selection.
        assertEquals("running-1", model.state.value.activeSessionId)
        assertFalse(model.state.value.capacityRejected)
        model.open(".")
        assertTrue(model.state.value.capacityRejected)
        assertEquals("running-1", model.state.value.activeSessionId)
    }

    @Test fun historyAndUnconfirmedStatesHaveDistinctPresentationWithoutCountingAsRunning() {
        fun state(
            phase: String,
            stopped: Boolean = false,
        ) = ManualTerminal.State("id", phase, ".", null, null, stopped)
        assertEquals(R.string.terminal_state_running, terminalStatusResource(state("RUNNING")))
        assertEquals(R.string.terminal_phase_stopped, terminalStatusResource(state("STOPPED", true)))
        assertEquals(R.string.terminal_phase_unknown, terminalStatusResource(state("UNKNOWN")))
        assertEquals(R.string.terminal_state_ended, terminalStatusResource(state("UNKNOWN", true)))
    }

    private class Terminal(
        initial: Int,
    ) : ManualTerminal {
        val store = InMemoryLineStore()
        private val numbering = TerminalWindowSlots(store)
        private val capacity = RuntimeExecutionCapacity()
        val records = linkedMapOf<String, ManualTerminal.State>()
        var acceptGate: CompletableDeferred<Unit>? = null
        var startFailure: String? = null
        var detachments = 0
        private var requests = initial
        override val maxSessions = 4

        init {
            repeat(initial) { index ->
                val id = "running-${index + 1}"
                check(capacity.reserveTerminal(id))
                records[id] = state(id)
            }
        }

        private fun state(id: String): ManualTerminal.State {
            val fresh = ManualTerminal.State(id, "RUNNING", ".", null, null, false)
            return numbering.present(records.values.filter { it.sessionId != id } + fresh).first { it.sessionId == id }
        }

        fun finish(id: String) {
            records[id] = records.getValue(id).copy(phase = "STOPPED", canSettle = true)
            capacity.releaseTerminal(id)
        }

        override suspend fun hasSession() = records.isNotEmpty()

        override suspend fun sessions() = records.values.toList()

        override suspend fun query(sessionId: String?) = records.getValue(requireNotNull(sessionId))

        override suspend fun start(
            relativeDirectory: String,
            leaseMs: Long,
        ): ManualTerminal.State {
            startFailure?.let { error(it) }
            val id = "running-${++requests}"
            check(capacity.reserveTerminal(id)) { "CAPACITY_FULL" }
            acceptGate?.await()
            return state(id).also { records[id] = it }
        }

        override suspend fun stop(sessionId: String?): ManualTerminal.State {
            finish(requireNotNull(sessionId))
            return records.getValue(sessionId)
        }

        override suspend fun settle(sessionId: String?) {
            records.remove(sessionId)
        }

        override suspend fun attach(sessionId: String?) =
            object : ManualTerminal.Connection {
                override suspend fun read(cursor: String?) = ManualTerminal.Output("0", byteArrayOf(), false, false)

                override suspend fun write(bytes: ByteArray) = "ACCEPTED"

                override suspend fun resize(
                    rows: Int,
                    columns: Int,
                ) = Unit

                override suspend fun detach() {
                    detachments++
                }
            }
    }
}
