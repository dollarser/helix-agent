package com.helix.app.terminal

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lifecycle entry uses the real ViewModel; the fake boundary never launches a shell. */
class TerminalEntryDeviceTest {
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

    @Test fun unknownAndSettledSessionsRequireExplicitHandling() =
        onMain {
            for (retained in listOf(state("old", "UNKNOWN"), state("old", "STOPPED", true))) {
                val terminal = FakeTerminal(retained)
                val model = ManualTerminalViewModel(terminal)
                model.enter(".")
                assertEquals(retained, model.state.value.session)
                assertEquals(0, terminal.starts)
                assertTrue(terminal.attached.isEmpty())
                assertNull(model.state.value.connection)
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
    ) : ManualTerminal {
        val retained = mutableListOf<ManualTerminal.State>().apply { initial?.let(::add) }
        var starts = 0
        var detaches = 0
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

        override suspend fun stop(sessionId: String?) = query(sessionId)

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
