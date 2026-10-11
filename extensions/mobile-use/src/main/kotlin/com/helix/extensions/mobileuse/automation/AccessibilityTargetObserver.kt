package com.helix.extensions.mobileuse.automation

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal data class AccessibilityTarget(
    val packageName: String,
    val windowId: Int,
)

/**
 * Conflates event bursts without querying nodes on Android's main looper. Dispatch publication,
 * request and close on the service looper so lifecycle changes cannot interleave publication.
 */
internal class AccessibilityTargetObserver(
    private val executor: Executor = worker,
    private val query: () -> AccessibilityTarget?,
    private val dispatch: (() -> Unit) -> Unit,
    private val observe: (AccessibilityTarget) -> Unit,
    private val windowChanged: () -> Unit = {},
) {
    private var revision = 0L
    private var scheduled = false
    private var closed = false
    private var verifiedWindow: Int? = null

    @Volatile var activeWindow: Int? = null
        private set

    @Synchronized
    fun request(windowMayHaveChanged: Boolean) {
        if (closed) return
        revision++
        if (windowMayHaveChanged) activeWindow = null
        if (scheduled) return
        scheduled = true
        try {
            executor.execute(::drain)
        } catch (_: RejectedExecutionException) {
            // A previous service's Binder query still owns capacity. A later event retries.
            scheduled = false
        }
    }

    @Synchronized
    fun close() {
        closed = true
        revision++
        activeWindow = null
        verifiedWindow = null
    }

    private fun drain() {
        while (true) {
            val version = synchronized(this) { if (closed) null else revision } ?: break
            val result = readTarget()
            val latest = synchronized(this) { !closed && revision == version }
            if (latest) dispatch { publish(version, result) }
            val finished =
                synchronized(this) {
                    if (closed || revision == version) {
                        scheduled = false
                        true
                    } else {
                        false
                    }
                }
            if (finished) return
        }
        synchronized(this) { scheduled = false }
    }

    private fun publish(
        version: Long,
        result: AccessibilityTarget?,
    ) {
        val changed =
            synchronized(this) {
                if (closed || revision != version) return
                val changed = verifiedWindow != null && verifiedWindow != result?.windowId
                activeWindow = result?.windowId
                verifiedWindow = result?.windowId
                changed
            }
        // Window events already invalidate synchronously; only a newly detected replacement
        // needs another invalidation. A first observation must not invalidate a fresh snapshot.
        if (changed) windowChanged()
        if (result != null) observe(result)
    }

    @Suppress("SwallowedException")
    private fun readTarget(): AccessibilityTarget? =
        try {
            query()
        } catch (_: RuntimeException) {
            // Disconnected/recycled Android windows are unavailable, never a verified target.
            null
        }

    companion object {
        // Shared across service recreation: cancellation does not free a blocked Binder call.
        private val worker =
            ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                ArrayBlockingQueue(1),
                { runnable -> Thread(runnable, "helix-accessibility-target").apply { isDaemon = true } },
            )
    }
}
