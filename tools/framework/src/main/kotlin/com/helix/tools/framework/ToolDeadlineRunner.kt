package com.helix.tools.framework

import com.helix.core.model.Clock
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal class ToolDeadlineRunner(
    private val clock: Clock,
    private val executorService: ExecutorService,
) {
    /**
     * Enforces the [ToolExecutor] contract: the executor must return within [call.deadline],
     * otherwise the dispatch settles as [ToolExecutorResult.TimedOut]. An implementation
     * blocked in I/O ignores the deadline on its own; without this watchdog the dispatch —
     * and, for a scheduled call, the whole batch — would hang until the kernel gives up.
     *
     * Each dispatch runs on its own daemon thread from a cached pool, so a stuck executor
     * thread is isolated: it cannot block another dispatch from starting. The
     * [Future.cancel] on timeout is best-effort (an interrupt the executor may ignore);
     * the model-visible outcome is the stable TIMEOUT either way. When the executor
     * throws BEFORE the deadline the original exception propagates unchanged — the
     * attempt is settled by [dispatch] as unknown side-effect state, exactly as with a
     * direct call.
     */
    @Suppress("SwallowedException") // the timeout settles as TimedOut, not an error to propagate
    fun executeWithinDeadline(
        executor: ToolExecutor,
        call: ExecutableToolCall,
    ): ToolExecutorResult? {
        val remaining = call.deadline.toEpochMilli() - clock.now().toEpochMilli()
        // null proves the executor was never submitted; submitted timeouts remain uncertain.
        if (remaining <= 0) return null
        val future: Future<ToolExecutorResult> =
            executorService.submit(
                Callable {
                    try {
                        executor.execute(call)
                    } finally {
                        // A timeout's cancel(true) may have flagged this pooled thread; clear the
                        // sticky interrupt so the NEXT dispatch reusing it doesn't fail spuriously.
                        Thread.interrupted()
                    }
                },
            )
        return try {
            awaitExecution(future, call.cancel, remaining)
        } catch (e: TimeoutException) {
            // The deadline passed: interrupt is best-effort, the outcome is settled as TIMEOUT.
            future.cancel(true)
            ToolExecutorResult.TimedOut
        } catch (e: ExecutionException) {
            rethrowExecutorFailure(e.cause ?: e)
        } catch (e: InterruptedException) {
            // The dispatch itself was interrupted: keep the interrupt flag, propagate as-is.
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw e
        }
    }

    @Suppress("SwallowedException") // polling timeout continues only while the monotonic deadline remains live
    private fun awaitExecution(
        future: Future<ToolExecutorResult>,
        cancel: CancelSignal,
        remainingMillis: Long,
    ): ToolExecutorResult {
        val started = System.nanoTime()
        val budget = TimeUnit.MILLISECONDS.toNanos(remainingMillis)
        while (true) {
            if (future.isDone || cancel.isCancelled()) {
                return if (future.isDone) {
                    future.get()
                } else {
                    future.cancel(true)
                    ToolExecutorResult.Cancelled
                }
            }
            val remaining = budget - (System.nanoTime() - started)
            if (remaining <= 0) throw TimeoutException()
            try {
                return future.get(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS)
            } catch (e: TimeoutException) {
                if (System.nanoTime() - started >= budget) throw e
            }
        }
    }

    /**
     * Unwraps the executor's own exception (thrown inside the submit pool) and rethrows it as if
     * it had been thrown inline — the caller's catch-all settles the attempt from it. A
     * contract-following Kotlin executor can only throw [RuntimeException]/[Error]; the fallback
     * guards a non-Kotlin implementation and keeps the root cause.
     */
    @Suppress("TooGenericExceptionThrown") // the fallback preserves the root cause, never swallows it
    private fun rethrowExecutorFailure(cause: Throwable): Nothing =
        when (cause) {
            is Error -> throw cause

            is RuntimeException -> throw cause

            // Fixed message: a checked cause's toString() may carry real paths (doc 10).
            else -> throw RuntimeException("tool executor threw a checked exception", cause)
        }
}
