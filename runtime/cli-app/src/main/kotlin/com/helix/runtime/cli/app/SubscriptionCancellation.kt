package com.helix.runtime.cli.app

import java.io.Closeable
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** One execution's sticky stop intent. Late resources cannot escape an earlier cancellation. */
internal class SubscriptionCancellation {
    private val lock = Any()
    private var cancelled = false
    private var active: Closeable? = null

    fun checkActive() {
        synchronized(lock) { if (cancelled) throw CancellationException("Subscription execution stopped") }
    }

    /** Mark the intent under the owner's state lock; invoke the captured close outside that lock. */
    fun request(): Closeable? =
        synchronized(lock) {
            cancelled = true
            active
        }

    fun cancel() {
        request()?.close()
    }

    fun <T : Closeable, R> using(
        resource: T,
        action: (T) -> R,
    ): R {
        val closed = AtomicBoolean()
        val once = Closeable { if (closed.compareAndSet(false, true)) resource.close() }
        try {
            synchronized(lock) {
                check(active == null) { "Execution resource already registered" }
                checkActive()
                active = once
            }
            checkActive()
            return action(resource)
        } finally {
            synchronized(lock) { if (active === once) active = null }
            once.close()
        }
    }
}
