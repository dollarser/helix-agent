package com.helix.app.terminal

import kotlinx.coroutines.channels.Channel

/** Coalesces small keyboard events without making capacity depend on the IME's fragmentation. */
internal class TerminalInputQueue {
    val ready = Channel<Unit>(Channel.CONFLATED)
    private val buffer = ByteArray(CAPACITY)
    private var head = 0
    private var size = 0
    private var closed = false

    @Synchronized
    fun offer(bytes: ByteArray): Boolean {
        if (closed || bytes.size > MAX_CHUNK || bytes.size > CAPACITY - size) return false
        if (bytes.isNotEmpty()) {
            val tail = (head + size) % CAPACITY
            val first = minOf(bytes.size, CAPACITY - tail)
            bytes.copyInto(buffer, tail, 0, first)
            bytes.copyInto(buffer, 0, first, bytes.size)
            size += bytes.size
            ready.trySend(Unit)
        }
        return true
    }

    /** One writer; a maximum of one additional MAX_CHUNK batch may be in flight. */
    @Synchronized
    fun poll(): ByteArray? {
        if (size == 0) return null
        val count = minOf(size, MAX_CHUNK)
        val result = ByteArray(count)
        val first = minOf(count, CAPACITY - head)
        buffer.copyInto(result, 0, head, head + first)
        buffer.copyInto(result, first, 0, count - first)
        head = (head + count) % CAPACITY
        size -= count
        return result
    }

    @Synchronized
    fun close() {
        closed = true
        size = 0
        buffer.fill(0)
        ready.close()
    }

    companion object {
        const val MAX_CHUNK = 8192
        const val CAPACITY = 32 * 1024
    }
}
