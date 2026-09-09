package com.helix.runtime.proot.app

import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal interface CaptureBudget {
    val hitLimit: AtomicBoolean

    fun take(want: Int): Int
}

/** Shared stdout+stderr budget (the spec's maxOutputBytes caps the COMBINED streams). */
internal class OutputBudget(
    limitBytes: Long,
) : CaptureBudget {
    private val remaining = AtomicLong(limitBytes)
    override val hitLimit = AtomicBoolean(false)

    /** Takes up to [want] bytes; returns the allowed amount (0 once the budget is spent). */
    override fun take(want: Int): Int {
        while (true) {
            val cur = remaining.get()
            if (cur <= 0) {
                hitLimit.set(true)
                return 0
            }
            val allow = minOf(cur, want.toLong()).toInt()
            if (remaining.compareAndSet(cur, cur - allow)) return allow
        }
    }
}

/** A stream-local cap layered over the shared combined-output cap. */
internal class StreamOutputBudget(
    private val shared: OutputBudget,
    limitBytes: Long,
) : CaptureBudget {
    private val remaining = AtomicLong(limitBytes)
    override val hitLimit: AtomicBoolean
        get() = shared.hitLimit

    override fun take(want: Int): Int {
        while (true) {
            val current = remaining.get()
            if (current <= 0) {
                hitLimit.set(true)
                return 0
            }
            val streamAllowed = minOf(current, want.toLong()).toInt()
            if (remaining.compareAndSet(current, current - streamAllowed)) {
                val allowed = shared.take(streamAllowed)
                if (allowed < want) hitLimit.set(true)
                return allowed
            }
        }
    }
}

/**
 * Capped stream capture sharing one [OutputBudget]: reads until EOF or the
 * budget is spent (flagging it so the watchdog kills the group — a job that
 * streams past its cap never runs on).
 */
internal class BoundedCapture(
    private val budget: CaptureBudget,
    private val onLimit: () -> Unit = {},
) {
    private val buffer = java.io.ByteArrayOutputStream()

    @Volatile
    var bytes: ByteArray = ByteArray(0)
        private set

    val truncated: Boolean
        get() = budget.hitLimit.get()

    // The pump has two legal exits (EOF, budget); both must stop immediately.
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // a read error is a short capture, not a crash
    fun drain(input: InputStream) {
        val chunk = ByteArray(65536)
        try {
            var n = input.read(chunk)
            while (n >= 0) {
                val allow = budget.take(n)
                if (allow > 0) buffer.write(chunk, 0, allow)
                if (allow < n) {
                    budget.hitLimit.set(true)
                    onLimit()
                    break
                }
                n = input.read(chunk)
            }
        } catch (e: Exception) {
            // stream closed by the kill: keep what was captured
        }
    }

    fun finish() {
        bytes = buffer.toByteArray()
    }
}
