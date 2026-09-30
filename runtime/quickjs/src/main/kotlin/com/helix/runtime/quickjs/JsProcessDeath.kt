package com.helix.runtime.quickjs

import java.util.concurrent.CountDownLatch

/** A transport reply, cancellation or failed transaction is not process-exit evidence. */
internal class JsProcessDeath {
    private val observed = CountDownLatch(1)

    fun record() = observed.countDown()

    fun isObserved(): Boolean = observed.count == 0L

    /** Even a fatal local control failure must not release a still-running native effect. */
    fun stopAndAwait(requestStop: () -> Unit) {
        try {
            requestStop()
        } finally {
            awaitObserved()
        }
    }

    /**
     * The ordinary executor keeps its effect permit until this original process has died.
     * Dispatcher deadlines can stop waiting for the executor, but cannot release its permit.
     * Restore interruption only after exit evidence; never infer exit from a wait timeout.
     */
    fun awaitObserved() {
        var interrupted = false
        try {
            while (!isObserved()) {
                try {
                    observed.await()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
