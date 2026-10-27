package com.helix.app.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalClosedPresentationTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined + Job())

    @After fun close() = scope.cancel()

    @Test fun stoppingLastTerminalClosesViewportWithoutDeletingFactsOrStartingAnother() {
        val terminal = Terminal()
        val model = ManualTerminalViewModel(terminal, scope)
        model.enter(".")
        model.stop()
        assertTrue(
            model.state.value.sessions
                .isEmpty(),
        )
        assertFalse(model.state.value.hasSession)
        assertNull(model.state.value.activeSessionId)
        assertNull(model.state.value.connection)
        assertEquals(1, terminal.starts)
        assertEquals(0, terminal.deletions)
        assertTrue(terminal.records.getValue("s1").canSettle)
        model.open(".")
        assertEquals("s2", model.state.value.activeSessionId)
        assertEquals(
            2L,
            model.state.value.session
                ?.displayNumber,
        )
    }

    @Test fun closedCurrentTerminalSwitchesToAnotherRunningOne() {
        val terminal = Terminal()
        val model = ManualTerminalViewModel(terminal, scope)
        model.enter(".")
        model.open(".")
        model.closeSession()
        assertEquals("s1", model.state.value.activeSessionId)
        assertEquals(
            listOf("s1"),
            model.state.value.sessions
                .map { it.sessionId },
        )
        assertNotNull(model.state.value.connection)
        assertEquals(2, terminal.starts)
        assertEquals(0, terminal.deletions)
        model.switchSession("s2")
        assertEquals("s1", model.state.value.activeSessionId)
    }

    @Test fun restartOmitsStoppedAndUnconnectableWindowsButRetainsTheirControlFacts() {
        val terminal = Terminal()
        terminal.records["closed"] = terminal.state("closed").copy(phase = "STOPPED", canSettle = true)
        terminal.records["unknown"] = terminal.state("unknown").copy(phase = "UNKNOWN")
        terminal.records["closing"] = terminal.state("closing").copy(phase = "STOPPING")
        val model = ManualTerminalViewModel(terminal, scope)
        assertEquals(
            setOf("closing"),
            model.state.value.sessions
                .map { it.sessionId }
                .toSet(),
        )
        assertEquals(0, terminal.deletions)
        assertEquals(0, terminal.starts)
        model.refresh()
        assertTrue(
            model.state.value.sessions
                .none { it.sessionId == "closed" },
        )
        assertTrue(terminal.records.containsKey("closed"))
        assertTrue(terminal.records.containsKey("unknown"))
    }

    @Test fun shellExitObservedAsynchronouslyClosesWithoutAutomaticReplacement() =
        runBlocking {
            val terminal = Terminal()
            val model = ManualTerminalViewModel(terminal, scope)
            model.enter(".")
            val connection = requireNotNull(model.state.value.connection)
            terminal.finish("s1")
            withTimeout(3000) {
                val observe = launch { model.observe(connection) }
                while (model.state.value.connection != null) delay(25)
                observe.join()
            }
            assertFalse(model.state.value.hasSession)
            assertEquals(1, terminal.starts)
            assertEquals(0, terminal.deletions)
        }

    private class Terminal : ManualTerminal {
        val records = linkedMapOf<String, ManualTerminal.State>()
        var starts = 0
        var deletions = 0

        fun state(id: String) = ManualTerminal.State(id, "RUNNING", ".", null, null, false, starts.toLong())

        fun finish(id: String) {
            records[id] = records.getValue(id).copy(phase = "STOPPED", canSettle = true)
        }

        override suspend fun hasSession() = records.isNotEmpty()

        override suspend fun sessions() = records.values.toList()

        override suspend fun query(sessionId: String?) = records.getValue(requireNotNull(sessionId))

        override suspend fun start(
            relativeDirectory: String,
            leaseMs: Long,
        ): ManualTerminal.State {
            starts++
            return state("s$starts").also { records[it.sessionId] = it }
        }

        override suspend fun stop(sessionId: String?): ManualTerminal.State {
            finish(requireNotNull(sessionId))
            return query(sessionId)
        }

        override suspend fun settle(sessionId: String?) {
            deletions++
            records.remove(sessionId)
        }

        override suspend fun attach(sessionId: String?) =
            object : ManualTerminal.Connection {
                override suspend fun read(cursor: String?) = ManualTerminal.Output("0", byteArrayOf(), false, false)

                override suspend fun write(bytes: ByteArray) = error("No input replay")

                override suspend fun resize(
                    rows: Int,
                    columns: Int,
                ) = Unit

                override suspend fun detach() = Unit
            }
    }
}
