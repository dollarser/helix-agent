package com.helix.app.terminal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalRecoveryActionsTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined + Job())

    @After fun tearDown() = scope.cancel()

    @Test fun unknownSessionCanStartIndependentSessionWithoutReplayingOrRetargeting() =
        runBlocking {
            val terminal = FakeTerminal()
            val model = ManualTerminalViewModel(terminal, scope)
            model.enter(".")
            assertEquals(1, terminal.starts)
            model.disconnect()
            model.enter(".")
            assertEquals(1, terminal.starts)
            assertEquals("new", model.state.value.activeSessionId)
            assertEquals("old", terminal.records.first().sessionId)
            assertEquals(0, terminal.stops)
            assertEquals(0, terminal.writes)
        }

    @Test fun unknownHistoryDoesNotBlockFreshStartAtSinglePhysicalSlot() =
        runBlocking {
            val terminal = FakeTerminal(limit = 1)
            val model = ManualTerminalViewModel(terminal, scope)
            model.open(".")
            assertEquals(null, model.state.value.errorMessage)
            assertEquals(1, terminal.starts)
            assertEquals(0, terminal.settlements)
        }

    @Test fun failedOldHistoryReadCannotPreventExplicitFreshStart() =
        runBlocking {
            val terminal = FakeTerminal().apply { historyFailure = true }
            val model = ManualTerminalViewModel(terminal, scope)
            assertTrue(model.state.value.failed)
            model.open(".")
            assertEquals("new", model.state.value.activeSessionId)
            assertFalse(model.state.value.failed)
            assertEquals(1, terminal.starts)
            assertEquals(0, terminal.stops)
        }

    @Test fun oldDetachFailureCannotDiscardOrBlockTheNewShell() =
        runBlocking {
            val terminal = FakeTerminal()
            val model = ManualTerminalViewModel(terminal, scope)
            model.enter(".")
            terminal.detachFailure = true
            model.open(".")
            assertEquals(2, terminal.starts)
            assertEquals("new", model.state.value.activeSessionId)
            assertTrue(model.state.value.connection != null)
            assertEquals("OLD_CONNECTION_UNCONFIRMED", model.state.value.errorMessage)
            assertEquals(0, terminal.stops)
        }

    @Test fun stopWithoutProofKeepsIdentityAndExposesRecoveryRatherThanPretendingSuccess() =
        runBlocking {
            val terminal = FakeTerminal()
            val model = ManualTerminalViewModel(terminal, scope)
            model.settle("old")
            assertEquals(1, terminal.stops)
            assertEquals(0, terminal.settlements)
            assertEquals("CLOSE_UNCONFIRMED", model.state.value.errorMessage)
            assertTrue(model.state.value.hasSession)
        }

    @Test fun provenExitCanCloseAndStartFreshWithoutReplayingInput() =
        runBlocking {
            val terminal = FakeTerminal(proveStop = true)
            val model = ManualTerminalViewModel(terminal, scope)
            model.settle("old")
            assertEquals(1, terminal.settlements)
            assertFalse(model.state.value.hasSession)
            model.open(".")
            assertEquals(1, terminal.starts)
            assertEquals(0, terminal.writes)
        }

    @Test fun clearingDisplayDoesNotStopOrForgetExecution() =
        runBlocking {
            val terminal = FakeTerminal()
            val model = ManualTerminalViewModel(terminal, scope)
            model.clearDisplay()
            assertEquals(1, model.state.value.displayRevision)
            assertEquals("old", model.state.value.activeSessionId)
            assertEquals(0, terminal.stops)
            assertEquals(0, terminal.settlements)
        }

    @Test fun freshStartNeverResetsOrDiscardsOldUnknownAndStoppedSessions() =
        runBlocking {
            val terminal = FakeTerminal()
            terminal.records += ManualTerminal.State("exited", "STOPPED", ".", null, 0, true)
            val model = ManualTerminalViewModel(terminal, scope)
            model.open(".")
            assertEquals(1, terminal.starts)
            assertEquals(0, terminal.stops)
            assertEquals(0, terminal.settlements)
            assertEquals(setOf("old", "exited", "new"), terminal.records.map { it.sessionId }.toSet())
            assertEquals("new", model.state.value.activeSessionId)
            assertEquals(0, terminal.writes)
        }

    @Test fun cancellationDuringStopDoesNotClearIdentityOrStartAnotherShell() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val terminal =
                FakeTerminal(stopGate = {
                    entered.complete(Unit)
                    awaitCancellation()
                })
            val job = launch { resetTerminalSessions(terminal) }
            entered.await()
            job.cancelAndJoin()
            assertEquals("old", terminal.records.single().sessionId)
            assertEquals(0, terminal.settlements)
            assertEquals(0, terminal.starts)
            assertEquals(0, terminal.writes)
        }

    private class FakeTerminal(
        private val limit: Int = 2,
        private val proveStop: Boolean = false,
        private val stopGate: (suspend () -> Unit)? = null,
    ) : ManualTerminal {
        override val maxSessions get() = limit
        val records = mutableListOf(ManualTerminal.State("old", "UNKNOWN", ".", null, null, false))
        var historyFailure = false
        var detachFailure = false
        var starts = 0
        var stops = 0
        var settlements = 0
        var writes = 0

        override suspend fun hasSession() = records.isNotEmpty()

        override suspend fun sessions(): List<ManualTerminal.State> {
            check(!historyFailure) { "History unavailable" }
            return records.toList()
        }

        override suspend fun query(sessionId: String?) = records.first { it.sessionId == sessionId }

        override suspend fun start(
            relativeDirectory: String,
            leaseMs: Long,
        ): ManualTerminal.State {
            starts++
            return ManualTerminal.State("new", "RUNNING", relativeDirectory, null, null, false).also(records::add)
        }

        override suspend fun stop(sessionId: String?): ManualTerminal.State {
            stops++
            stopGate?.invoke()
            val current = query(sessionId)
            return if (proveStop) {
                current.copy(phase = "STOPPED", canSettle = true).also {
                    records[records.indexOf(current)] = it
                }
            } else {
                current
            }
        }

        override suspend fun settle(sessionId: String?) {
            check(query(sessionId).canSettle)
            settlements++
            records.removeAll { it.sessionId == sessionId }
        }

        override suspend fun attach(sessionId: String?) =
            object : ManualTerminal.Connection {
                override suspend fun read(cursor: String?) = ManualTerminal.Output("0", byteArrayOf(), false, false)

                override suspend fun write(bytes: ByteArray): String {
                    writes++
                    return "ACCEPTED"
                }

                override suspend fun resize(
                    rows: Int,
                    columns: Int,
                ) = Unit

                override suspend fun detach() {
                    check(!detachFailure) { "Old detach failed" }
                }
            }
    }
}
