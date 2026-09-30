package com.helix.runtime.quickjs

import java.util.concurrent.TimeUnit

/** One private-process watchdog, independent of the main Looper, Binder pool and script thread. */
internal class JsNativeWatchdog(
    initialTimeoutMs: Long,
    private val terminate: () -> Unit,
) {
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN") // JVM monitor supports notification and a nanosecond deadline.
    private val changed = Object()
    private var deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(initialTimeoutMs)
    private var stopping = false
    private var started = false

    init {
        require(initialTimeoutMs in 1..60_000)
    }

    fun start() {
        synchronized(changed) {
            check(!started) { "Native watchdog already started" }
            Thread(::run, "helix-js-native-watchdog").apply { isDaemon = true }.start()
            started = true
        }
    }

    /** Validated execution deadlines may shorten the process lifetime, never extend it. */
    fun shortenTo(deadline: Long) {
        synchronized(changed) {
            val now = System.nanoTime()
            if (deadline - now < deadlineNanos - now) deadlineNanos = deadline
            changed.notifyAll()
        }
    }

    fun requestStop() {
        synchronized(changed) {
            stopping = true
            changed.notifyAll()
        }
    }

    private fun run() {
        synchronized(changed) {
            while (!stopping) {
                val remaining = deadlineNanos - System.nanoTime()
                if (remaining <= 0) break
                try {
                    TimeUnit.NANOSECONDS.timedWait(changed, remaining)
                } catch (_: InterruptedException) {
                    // Interruption must not disable the private process's hard lifetime bound.
                    stopping = true
                }
            }
        }
        terminate()
    }
}
