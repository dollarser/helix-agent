package com.helix.app.pdf

import com.helix.tools.files.PdfReadException
import com.helix.tools.files.PdfSourceSnapshot
import com.helix.tools.framework.ExecutableToolCall
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One native parser. A cancelled host never frees a still-running parser's slot. */
internal class PdfParseCapacity {
    private val slot = Semaphore(1, true)

    fun acquire(call: ExecutableToolCall): Lease {
        PdfSourceSnapshot.checkActive(call)
        while (!slot.tryAcquire(25, TimeUnit.MILLISECONDS)) PdfSourceSnapshot.checkActive(call)
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
            if (released.get()) throw PdfReadException("PARSER_DIED")
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
