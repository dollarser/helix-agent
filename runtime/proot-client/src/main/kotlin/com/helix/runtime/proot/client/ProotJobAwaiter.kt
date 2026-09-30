package com.helix.runtime.proot.client

import com.helix.runtime.proot.client.ProotJobClient.AwaitOutcome
import com.helix.runtime.proot.client.ProotJobClient.JobStateOutcome
import com.helix.runtime.proot.ipc.UnavailableCause

/** Polling observes; an explicit caller stop additionally cancels the original job exactly once. */
internal class ProotJobAwaiter(
    private val clock: () -> Long,
    private val query: (String) -> JobStateOutcome,
    private val cancel: (String) -> JobStateOutcome,
    private val pause: (Long) -> Unit = Thread::sleep,
) {
    @Suppress("ReturnCount") // One return per observed terminal/stop/budget boundary.
    fun await(
        jobId: String,
        pollMs: Long,
        timeoutMs: Long,
        shouldContinue: () -> Boolean,
    ): AwaitOutcome {
        require(timeoutMs >= 0 && pollMs > 0)
        val start = clock()
        while (true) {
            if (Thread.currentThread().isInterrupted || !shouldContinue()) return stop(jobId)
            val elapsed = clock() - start
            if (elapsed < 0 || elapsed > timeoutMs) return AwaitOutcome.TimedOut
            val result = queryOriginal(jobId)
            if (Thread.currentThread().isInterrupted || !shouldContinue()) return stop(jobId)
            val terminal = terminal(result)
            if (terminal != null) return terminal
            try {
                pause(pollMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return stop(jobId)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // Query failure still reaches the stop-intent check; never implies exit.
    private fun queryOriginal(jobId: String): JobStateOutcome =
        try {
            query(jobId)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            JobStateOutcome.Refused(UnavailableCause.PROTOCOL_MISMATCH)
        } catch (_: Exception) {
            JobStateOutcome.Refused(UnavailableCause.PROTOCOL_MISMATCH)
        }

    @Suppress("TooGenericExceptionCaught") // Transport failure means stop delivery unconfirmed, never stopped.
    private fun stop(jobId: String): AwaitOutcome.StopRequested {
        val interrupted = Thread.interrupted()
        return try {
            val reply =
                try {
                    cancel(jobId)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    JobStateOutcome.Refused(UnavailableCause.PROTOCOL_MISMATCH)
                } catch (_: Exception) {
                    JobStateOutcome.Refused(UnavailableCause.PROTOCOL_MISMATCH)
                }
            AwaitOutcome.StopRequested(reply)
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun terminal(outcome: JobStateOutcome): AwaitOutcome? =
        when (outcome) {
            is JobStateOutcome.Ok -> {
                when {
                    !outcome.record.state.isTerminal -> null
                    outcome.record.evidenceExpired -> AwaitOutcome.Interrupted(outcome.record)
                    else -> AwaitOutcome.Terminal(outcome.record)
                }
            }

            JobStateOutcome.Unknown -> {
                AwaitOutcome.Unknown
            }

            is JobStateOutcome.Refused -> {
                AwaitOutcome.Unavailable(outcome.cause)
            }
        }
}
