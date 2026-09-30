package com.helix.runtime.cli.app

/** Main-thread handoff: an OAuth code waits only in memory until the login UI resumes. */
internal class ForegroundLoginHandoff<T> {
    private var resumed = false
    private var pending: T? = null

    fun offer(value: T): T? {
        pending = value
        return take()
    }

    fun resume(): T? {
        resumed = true
        return take()
    }

    fun pause() {
        resumed = false
    }

    fun clear() {
        pending = null
    }

    private fun take(): T? = if (resumed) pending.also { pending = null } else null
}
