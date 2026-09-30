package com.helix.runtime.proot.client

import com.helix.runtime.proot.client.ProotJobClient.AwaitOutcome
import com.helix.runtime.proot.client.ProotJobClient.JobStateOutcome
import com.helix.runtime.proot.ipc.ProotJobRecord
import com.helix.runtime.proot.ipc.ProotJobState
import com.helix.runtime.proot.ipc.UnavailableCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProotJobAwaiterTest {
    private val id = "job_abcdef000001"
    private val running = ProotJobRecord(id, "exec_abcdef000001", "a".repeat(64), ProotJobState.RUNNING, 1)

    @Test fun callerStopDuringPollingCancelsOnlyOriginalJobOnce() {
        var keepGoing = true
        val cancelled = mutableListOf<String>()
        val waiter =
            ProotJobAwaiter({ 0L }, { JobStateOutcome.Ok(running) }, {
                cancelled += it
                JobStateOutcome.Ok(running)
            }, { keepGoing = false })
        val result = waiter.await(id, 1, 100) { keepGoing }
        assertEquals(AwaitOutcome.StopRequested(JobStateOutcome.Ok(running)), result)
        assertEquals(listOf(id), cancelled)
    }

    @Test fun interruptedSleepAllowsControlBindAndRestoresInterrupt() {
        var cancellations = 0
        val waiter =
            ProotJobAwaiter({ 0L }, { JobStateOutcome.Ok(running) }, {
                assertFalse(Thread.currentThread().isInterrupted)
                cancellations++
                JobStateOutcome.Unknown
            }, { throw InterruptedException("fixture") })
        try {
            assertEquals(AwaitOutcome.StopRequested(JobStateOutcome.Unknown), waiter.await(id, 1, 100) { true })
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(1, cancellations)
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun interruptedQueryStillDeliversStopAndPreservesInterrupt() {
        var cancellations = 0
        val waiter =
            ProotJobAwaiter({ 0L }, { throw InterruptedException("query interrupted") }, {
                assertFalse(Thread.currentThread().isInterrupted)
                cancellations++
                JobStateOutcome.Ok(running)
            })
        try {
            assertEquals(AwaitOutcome.StopRequested(JobStateOutcome.Ok(running)), waiter.await(id, 1, 100) { true })
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(1, cancellations)
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun queryFailureWithoutStopRemainsUnavailableAndDoesNotCancel() {
        val waiter = ProotJobAwaiter({ 0L }, { throw java.io.IOException("lost query") }, { error("no stop") })
        assertEquals(AwaitOutcome.Unavailable(UnavailableCause.PROTOCOL_MISMATCH), waiter.await(id, 1, 100) { true })
    }

    @Test fun stopDeliveryFailureNeverMeansExited() {
        val waiter = ProotJobAwaiter({ 0L }, { error("must not query") }, { throw java.io.IOException() })
        assertEquals(
            AwaitOutcome.StopRequested(JobStateOutcome.Refused(UnavailableCause.PROTOCOL_MISMATCH)),
            waiter.await(id, 1, 100) { false },
        )
    }

    @Test fun stopDuringAQueryWinsOverItsLateReplyAndCancelsOnce() {
        var keepGoing = true
        var cancellations = 0
        val waiter =
            ProotJobAwaiter({ 0L }, {
                keepGoing = false
                JobStateOutcome.Ok(running)
            }, {
                assertEquals(id, it)
                cancellations++
                JobStateOutcome.Ok(running.copy(state = ProotJobState.CANCELLED, terminalAtEpochMs = 2))
            })
        val stopped = waiter.await(id, 1, 100) { keepGoing } as AwaitOutcome.StopRequested
        assertEquals(ProotJobState.CANCELLED, (stopped.reply as JobStateOutcome.Ok).record.state)
        assertEquals(1, cancellations)
    }

    @Test fun invalidPollingParametersNeverQueryOrCancel() {
        val waiter = ProotJobAwaiter({ 0L }, { error("no query") }, { error("no cancel") })
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            waiter.await(id, 0, 1) { true }
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            waiter.await(id, 1, -1) { true }
        }
    }

    @Test fun ordinaryCompletionAndBudgetExpiryDoNotCancel() {
        val ended = running.copy(state = ProotJobState.CANCELLED, terminalAtEpochMs = 2)
        val waiter = ProotJobAwaiter({ 0L }, { JobStateOutcome.Ok(ended) }, { error("no cancel") })
        assertEquals(AwaitOutcome.Terminal(ended), waiter.await(id, 1, 100) { true })
        var now = 0L
        val timed = ProotJobAwaiter({ now }, { JobStateOutcome.Ok(running) }, { error("no cancel") }, { now += it })
        assertEquals(AwaitOutcome.TimedOut, timed.await(id, 1, 1) { true })
    }
}
