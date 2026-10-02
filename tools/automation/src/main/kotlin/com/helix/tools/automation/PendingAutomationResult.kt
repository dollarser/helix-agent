package com.helix.tools.automation

import com.helix.tools.framework.ExecutableToolCall
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Owns a single Android callback, including disposal when a deadline/cancel wins the race. */
internal class PendingAutomationResult<T : Any>(
    private val dispose: (T) -> Unit = {},
) {
    private val latch = CountDownLatch(1)
    private var value: T? = null
    private var closed = false

    @Synchronized
    fun complete(result: T) {
        if (closed || value != null) dispose(result) else value = result
        latch.countDown()
    }

    fun await(call: ExecutableToolCall): T? {
        try {
            while (!call.cancel.isCancelled() && Instant.now().isBefore(call.deadline)) {
                if (latch.await(50, TimeUnit.MILLISECONDS)) {
                    return takeIfCurrent(call)
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        abandon()
        return null
    }

    private fun takeIfCurrent(call: ExecutableToolCall): T? {
        if (!call.cancel.isCancelled() && Instant.now().isBefore(call.deadline)) return take()
        abandon()
        return null
    }

    @Synchronized
    private fun take(): T? {
        closed = true
        return value.also { value = null }
    }

    @Synchronized
    fun abandon() {
        closed = true
        value?.let(dispose)
        value = null
        latch.countDown()
    }
}
