package com.helix.runtime.proot.core

import java.util.ArrayDeque

/**
 * Runtime-side pending manual input. The session owner validates the current writer before
 * admission. Native writes happen outside this lock and must handle partial writes in order.
 * An accepted chunk is queued, not proof of execution; callers must never blindly replay it.
 */
class PtyInputBuffer {
    companion object {
        const val MAX_CHUNK_BYTES = 8192
        const val MAX_PENDING_BYTES = 32 * 1024
        const val MAX_PENDING_CHUNKS = 64
    }

    enum class Admission { ACCEPTED, FULL, CLOSED }

    private val pending = ArrayDeque<ByteArray>()
    private var pendingBytes = 0
    private var closed = false

    /** All or nothing, including large paste chunks. An over-limit request is never truncated. */
    @Synchronized
    fun offer(bytes: ByteArray): Admission {
        require(bytes.size in 1..MAX_CHUNK_BYTES)
        return when {
            closed -> {
                Admission.CLOSED
            }

            pending.size == MAX_PENDING_CHUNKS || bytes.size > MAX_PENDING_BYTES - pendingBytes -> {
                Admission.FULL
            }

            else -> {
                pending.addLast(bytes.copyOf())
                pendingBytes += bytes.size
                Admission.ACCEPTED
            }
        }
    }

    /** Single native writer only. At most one additional MAX_CHUNK_BYTES can be in flight. */
    @Synchronized
    fun poll(): ByteArray? = pending.pollFirst()?.also { pendingBytes -= it.size }

    /** Reject new input and discard pending bytes; the owner separately stops/reconciles execution. */
    @Synchronized
    fun close(): Int {
        closed = true
        val discarded = pendingBytes
        pending.clear()
        pendingBytes = 0
        return discarded
    }
}
