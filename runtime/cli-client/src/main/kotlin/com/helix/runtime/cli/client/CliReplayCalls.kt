package com.helix.runtime.cli.client

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One physical maintenance IPC, no queue. A timed-out Binder call retains its worker until it actually exits. */
internal class CliReplayCalls : AutoCloseable {
    private val worker =
        ThreadPoolExecutor(
            0,
            1,
            30,
            TimeUnit.SECONDS,
            SynchronousQueue(),
            { runnable -> Thread(runnable, "helix-replay-maintenance").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )

    fun <T> call(
        timeoutMillis: Long = 30_000,
        action: () -> T,
    ): T {
        require(timeoutMillis > 0)
        val result = worker.submit(Callable(action))
        try {
            return result.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (failed: ExecutionException) {
            throw IllegalStateException("REPLAY_MAINTENANCE_FAILED", failed)
        } finally {
            // Requests interruption only; a blocked native call keeps the sole physical worker occupied.
            if (!result.isDone) result.cancel(true)
        }
    }

    override fun close() {
        worker.shutdownNow()
    }
}
