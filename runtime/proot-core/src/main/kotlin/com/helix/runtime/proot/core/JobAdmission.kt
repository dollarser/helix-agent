package com.helix.runtime.proot.core

/** Start and cancellation share one boundary; an earlier cancel can never be followed by a start. */
class JobAdmission {
    private var cancelled = false

    @Synchronized
    fun <T : Any> start(action: () -> T): T? = if (cancelled) null else action()

    @Synchronized
    fun <T> cancel(action: () -> T): T {
        cancelled = true
        return action()
    }
}
