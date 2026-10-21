package com.helix.app.terminal

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lifecycle entry uses the real ViewModel; the fake boundary never launches a shell. */
@Suppress("TooManyFunctions") // Independent lifecycle and capacity boundary regressions.
class TerminalEntryDeviceTest {
    @Test fun configuredFourTerminalCapacityAllowsFourAndCloseReleasesOne() =
        onMain {
            val terminal = FakeTerminal(maxSessions = 4)
            val model = ManualTerminalViewModel(terminal)
            model.enter(".")
            repeat(3) { model.open(".") }
            assertEquals(4, terminal.starts)
            assertEquals(4, model.state.value.maxSessions)
            model.open(".")
            assertEquals(4, terminal.starts)
            assertEquals("CAPACITY_FULL", model.state.value.errorMessage)
            model.settle()
            assertEquals(3, terminal.retained.size)
            model.open(".")
            assertEquals(5, terminal.starts)
            assertEquals(4, terminal.retained.size)
            assertNull(model.state.value.errorMessage)
            model.disconnect()
        }

    @Test fun provenStoppedHistoryDoesNotFillTheActiveLimitButUnknownDoes() =
        onMain {
            val terminal = FakeTerminal(state("stopped", "STOPPED", true), maxSessions = 1)
            val model = ManualTerminalViewModel(terminal)
            model.enter(".")
            model.open(".")
            assertEquals(1, terminal.starts)
            model.disconnect()
            val unknown = FakeTerminal(state("unknown", "UNKNOWN"), maxSessions = 1)
            val other = ManualTerminalViewModel(unknown)
            other.enter(".")
            other.open(".")
            assertEquals(0, unknown.starts)
            assertEquals("CAPACITY_FULL", other.state.value.errorMessage)
        }

    @Test fun entryStartsOnceAndBackgroundReturnAttachesTheOriginalSession() =
        onMain {
            val terminal = FakeTerminal()
            val model = ManualTerminalViewModel(terminal)
            model.enter(".")
            model.enter(".")
            assertEquals(1, terminal.starts)
            assertEquals(listOf("s1"), terminal.attached)
            model.disconnect()
            model.enter(".")
            assertEquals(1, terminal.starts)
            assertEquals(listOf("s1", "s1"), terminal.attached)
            assertEquals(1, terminal.detaches)
            model.disconnect()
        }

    @Test fun existingSessionIsAttachedWithoutStartingAnother() =
        onMain {
            val terminal = FakeTerminal(state("existing"))
            val model = ManualTerminalViewModel(terminal)
            model.enter("different-directory")
            assertEquals(0, terminal.starts)
            assertEquals(listOf("existing"), terminal.attached)
            model.disconnect()
        }

    @Test fun unknownAndSettledHistoryAllowsIndependentFreshShellWithCapacity() =
        onMain {
            for (retained in listOf(state("old", "UNKNOWN"), state("old", "STOPPED", true))) {
                val terminal = FakeTerminal(retained)
                val model = ManualTerminalViewModel(terminal)
                model.enter(".")
                assertEquals(retained, terminal.retained.first { it.sessionId == "old" })
                assertEquals(1, terminal.starts)
                assertEquals("s1", model.state.value.activeSessionId)
                assertEquals(listOf("s1"), terminal.attached)
                model.disconnect()
            }
        }

    @Test fun ambiguousAutomaticStartIsNotReplayedOnResume() =
        onMain {
            val terminal = FakeTerminal().apply { startFailure = IllegalStateException("transport lost") }
            val model = ManualTerminalViewModel(terminal)
            model.enter(".")
            assertTrue(model.state.value.failed)
            model.enter(".")
            assertEquals(1, terminal.starts)
            assertNull(model.state.value.connection)
            terminal.startFailure = null
            model.open(".")
            assertEquals(2, terminal.starts)
            assertEquals("s2", model.state.value.activeSessionId)
            model.disconnect()
        }

    @Test fun explicitReconnectAndTabSwitchNeverAttachAnUnknownOrStoppedTerminal() =
        onMain {
            for (retained in listOf(
                state("old", "UNKNOWN"),
                state("old", "UNKNOWN", true),
                state("old", "STOPPED", true),
            )) {
                val terminal = FakeTerminal(retained)
                val model = ManualTerminalViewModel(terminal)
                model.enter(".")
                model.open(null)
                model.switchSession("old")
                assertTrue(terminal.attached.isNotEmpty())
                assertTrue(terminal.attached.none { it == "old" })
                assertEquals(1, terminal.starts)
                assertFalse(model.state.value.failed)
                assertNull(model.state.value.connection)
                model.disconnect()
            }
        }

    @Test fun closingReplacedTerminalsAllowsExplicitNewStartWithoutReplayingOldCommands() =
        onMain {
            val terminal = FakeTerminal(state("old1", "UNKNOWN", true))
            terminal.retained.add(state("old2", "UNKNOWN", true))
            val model = ManualTerminalViewModel(terminal)
            model.enter(".")
            assertEquals("s1", model.state.value.activeSessionId)
            model.switchSession("old1")
            model.settle()
            assertEquals("old2", model.state.value.activeSessionId)
            assertFalse(model.state.value.failed)
            assertEquals(listOf("s1"), terminal.attached)
            model.open(".")
            assertEquals(2, terminal.starts)
            assertEquals(listOf("s1", "s2"), terminal.attached)
            assertTrue(terminal.retained.none { it.sessionId == "old1" })
            model.disconnect()
        }

    @Test fun closeStopsTheRunningTerminalAndReleasesItInOneAction() =
        onMain {
            val terminal = FakeTerminal(state("live"))
            val model = ManualTerminalViewModel(terminal)
            model.enter(".")
            model.settle()
            assertEquals(1, terminal.stops)
            assertTrue(terminal.retained.isEmpty())
            assertFalse(model.state.value.hasSession)
            assertFalse(model.state.value.failed)
            assertNull(model.state.value.connection)
        }

    @Test fun cancelledAutomaticStartDoesNotBecomeSuccessOrRepeat() =
        onMain {
            val terminal = FakeTerminal().apply { startFailure = CancellationException("cancelled") }
            val model = ManualTerminalViewModel(terminal)
            model.enter(".")
            model.enter(".")
            assertEquals(1, terminal.starts)
            assertFalse(model.state.value.busy)
            assertFalse(model.state.value.hasSession)
            assertNull(model.state.value.connection)
        }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private class FakeTerminal(
        initial: ManualTerminal.State? = null,
        override val maxSessions: Int = 2,
    ) : ManualTerminal {
        val retained = mutableListOf<ManualTerminal.State>().apply { initial?.let(::add) }
        var starts = 0
        var detaches = 0
        var stops = 0
        var startFailure: Exception? = null
        val attached = mutableListOf<String>()

        override suspend fun hasSession() = retained.isNotEmpty()

        override suspend fun sessions() = retained.toList()

        override suspend fun query(sessionId: String?) =
            retained.first { sessionId == null || it.sessionId == sessionId }

        override suspend fun start(
            relativeDirectory: String,
            leaseMs: Long,
        ): ManualTerminal.State {
            starts++
            startFailure?.let { throw it }
            return state("s$starts").also(retained::add)
        }

        override suspend fun stop(sessionId: String?): ManualTerminal.State {
            stops++
            val original = query(sessionId)
            return original.copy(phase = "STOPPED", canSettle = true).also {
                retained[retained.indexOf(original)] = it
            }
        }

        override suspend fun settle(sessionId: String?) {
            retained.remove(query(sessionId))
        }

        override suspend fun attach(sessionId: String?): ManualTerminal.Connection {
            attached.add(query(sessionId).sessionId)
            return object : ManualTerminal.Connection {
                override suspend fun read(cursor: String?) = ManualTerminal.Output("0", byteArrayOf(), false, false)

                override suspend fun write(bytes: ByteArray) = error("Entry must never write input")

                override suspend fun resize(
                    rows: Int,
                    columns: Int,
                ) = Unit

                override suspend fun detach() {
                    detaches++
                }
            }
        }
    }

    companion object {
        private fun state(
            id: String,
            phase: String = "RUNNING",
            ended: Boolean = false,
        ) = ManualTerminal.State(id, phase, ".", null, null, ended)
    }
}
