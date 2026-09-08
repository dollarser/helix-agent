package com.helix.runtime.cli.client

import com.helix.runtime.cli.client.CliModelJobClient.AwaitOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CliModelJobAwaiterTest {
    private val running = CliModelJobRecord("job_134000000001", "a".repeat(64), CliModelJobState.RUNNING, 1L)
    private val terminal = running.copy(state = CliModelJobState.CANCELLED, terminalAtEpochMillis = 2L)
    private val accepted = CliModelWireResult(CliRuntimeProtocol.REPLY_JOB_ACCEPTED, running)

    @Test fun rejectedSubmissionDoesNotPollOrRetry() {
        val awaiter = CliModelJobAwaiter { error("must not transact") }
        assertEquals(
            AwaitOutcome.Unavailable(CliRuntimeVerification.Cause.HANDSHAKE_FAILED),
            awaiter.await(CliModelWireResult(CliRuntimeProtocol.REPLY_JOB_BUSY), 100L, 1L),
        )
    }

    @Test fun terminalDuplicateOnlyReconciles() {
        val calls = mutableListOf<Int>()
        val awaiter =
            CliModelJobAwaiter { code ->
                calls += code
                CliModelWireResult(CliRuntimeProtocol.REPLY_JOB_STATE, terminal)
            }
        val duplicate = CliModelWireResult(CliRuntimeProtocol.REPLY_JOB_DUPLICATE, terminal)
        assertEquals(AwaitOutcome.Terminal(terminal), awaiter.await(duplicate, 100L, 1L))
        assertEquals(listOf(CliRuntimeProtocol.TRANSACTION_JOB_FETCH_RESULT), calls)
    }

    @Test fun queryFailureReturnsWithoutRetryOrCancel() {
        val calls = mutableListOf<Int>()
        val awaiter =
            CliModelJobAwaiter { code ->
                calls += code
                CliModelWireResult(cause = CliRuntimeVerification.Cause.HANDSHAKE_FAILED)
            }
        assertEquals(
            AwaitOutcome.Unavailable(CliRuntimeVerification.Cause.HANDSHAKE_FAILED),
            awaiter.await(accepted, 100L, 1L),
        )
        assertEquals(listOf(CliRuntimeProtocol.TRANSACTION_JOB_QUERY), calls)
    }

    @Test fun terminalQueryReconcilesExactlyOnce() {
        val calls = mutableListOf<Int>()
        val awaiter =
            CliModelJobAwaiter { code ->
                calls += code
                CliModelWireResult(CliRuntimeProtocol.REPLY_JOB_STATE, terminal)
            }
        assertEquals(AwaitOutcome.Terminal(terminal), awaiter.await(accepted, 100L, 1L))
        assertEquals(
            listOf(CliRuntimeProtocol.TRANSACTION_JOB_QUERY, CliRuntimeProtocol.TRANSACTION_JOB_FETCH_RESULT),
            calls,
        )
    }

    @Test fun deadlineIncludesBoundaryPollThenCancelsExactlyOnce() {
        var now = 0L
        val calls = mutableListOf<Int>()
        val awaiter =
            CliModelJobAwaiter(clock = { now }, pause = { now += it }) { code ->
                calls += code
                CliModelWireResult(CliRuntimeProtocol.REPLY_JOB_STATE, running)
            }
        assertEquals(AwaitOutcome.TimedOut, awaiter.await(accepted, 0L, 1L))
        assertEquals(
            listOf(CliRuntimeProtocol.TRANSACTION_JOB_QUERY, CliRuntimeProtocol.TRANSACTION_JOB_CANCEL),
            calls,
        )
    }

    @Test fun interruptedWaitCancelsSameJobAndPreservesFlag() {
        val calls = mutableListOf<Int>()
        val awaiter =
            CliModelJobAwaiter(pause = { throw InterruptedException("test wait") }) { code ->
                calls += code
                CliModelWireResult(CliRuntimeProtocol.REPLY_JOB_STATE, running)
            }
        try {
            assertEquals(AwaitOutcome.TimedOut, awaiter.await(accepted, 100L, 1L))
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(
                listOf(CliRuntimeProtocol.TRANSACTION_JOB_QUERY, CliRuntimeProtocol.TRANSACTION_JOB_CANCEL),
                calls,
            )
        } finally {
            Thread.interrupted()
        }
    }
}
