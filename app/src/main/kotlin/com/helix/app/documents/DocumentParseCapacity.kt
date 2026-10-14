package com.helix.app.documents

import com.helix.extensions.documents.DocumentException
import com.helix.extensions.documents.DocumentLimits
import com.helix.tools.framework.ExecutableToolCall
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One native parser. A cancelled host never frees a still-running parser's slot. */
internal class DocumentParseCapacity {
    private val slot = Semaphore(1, true)

    fun acquire(call: ExecutableToolCall): Lease {
        DocumentLimits.checkActive(call)
        while (!slot.tryAcquire(25, TimeUnit.MILLISECONDS)) DocumentLimits.checkActive(call)
        return Lease(slot::release)
    }

    class Lease(
        private val release: () -> Unit,
    ) {
        private val started = AtomicBoolean(false)
        private val released = AtomicBoolean(false)
        private val exited = CountDownLatch(1)
        val awaitingExit: Boolean get() = started.get() && !released.get()

        fun beginNative() {
            if (released.get()) throw DocumentException("PARSER_DIED")
            started.set(true)
        }

        fun nativeExited() {
            releaseOnce()
            exited.countDown()
        }

        fun awaitExit(
            timeout: Long,
            unit: TimeUnit,
        ): Boolean = exited.await(timeout, unit)

        fun finishHost() {
            if (!started.get()) releaseOnce()
        }

        private fun releaseOnce() {
            if (released.compareAndSet(false, true)) release()
        }
    }
}
