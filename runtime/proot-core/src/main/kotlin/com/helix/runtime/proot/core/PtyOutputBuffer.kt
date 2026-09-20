package com.helix.runtime.proot.core

/** Raw PTY bytes, including escape sequences: decode only after ordered delivery to the renderer. */
data class PtyOutputPage(
    val cursor: String,
    val bytes: ByteArray,
    val gapBefore: Boolean,
    val eof: Boolean,
)

/**
 * Runtime-owned recent output. A slow/detached reader never waits in the PTY drain path.
 * Cursors bind this session generation, not a PID. A gap requires the client to reset its
 * parser/display and show lost output before consuming the tail; it must not splice streams.
 * EOF means output drained, NOT that the process or its jobs stopped or ownership can settle.
 * This process-local preview is not a transcript, authorization check, or recovery journal.
 */
class PtyOutputBuffer(
    sessionId: String,
    generation: String,
    capacity: Int = CAPACITY_BYTES,
) {
    companion object {
        const val CHUNK_BYTES = 8192
        const val CAPACITY_BYTES = 256 * 1024
        private val ID = Regex("[A-Za-z0-9_-]{1,64}")
    }

    init {
        require(ID.matches(sessionId) && ID.matches(generation))
        require(capacity in 1..CAPACITY_BYTES)
    }

    private val prefix = "$sessionId:$generation:"
    private val ring = ByteArray(capacity)
    private var written = 0L
    private var ended = false

    /** The single PTY drain supplies bounded chunks; no consumer, disk, or callback runs here. */
    @Synchronized
    fun append(
        bytes: ByteArray,
        length: Int = bytes.size,
    ) {
        require(length in 0..minOf(bytes.size, CHUNK_BYTES))
        check(!ended) { "PTY output already finished" }
        val end = Math.addExact(written, length.toLong())
        val retained = minOf(length, ring.size)
        val start = end - retained
        copyIntoRing(bytes, length - retained, start, retained)
        written = end
    }

    @Synchronized
    fun finish() {
        ended = true
    }

    /** Re-reading an overwritten cursor reports a gap; a future or foreign cursor is rejected. */
    @Synchronized
    fun read(
        cursor: String? = null,
        maxBytes: Int = CHUNK_BYTES,
    ): PtyOutputPage {
        require(maxBytes in 1..CHUNK_BYTES)
        val requested = parseCursor(cursor)
        require(requested in 0..written) { "PTY cursor is ahead of output" }
        val oldest = (written - ring.size).coerceAtLeast(0)
        val start = maxOf(requested, oldest)
        val size = minOf(written - start, maxBytes.toLong()).toInt()
        val bytes = ByteArray(size)
        val index = (start % ring.size).toInt()
        val first = minOf(size, ring.size - index)
        ring.copyInto(bytes, 0, index, index + first)
        ring.copyInto(bytes, first, 0, size - first)
        val next = start + size
        return PtyOutputPage("$prefix$next", bytes, requested < oldest, ended && next == written)
    }

    private fun parseCursor(cursor: String?): Long {
        if (cursor == null) return 0
        require(cursor.length <= prefix.length + 19 && cursor.startsWith(prefix)) { "Wrong PTY generation" }
        val offset = cursor.removePrefix(prefix)
        val parsed = offset.toLongOrNull()
        require(parsed != null && parsed >= 0 && parsed.toString() == offset) { "Invalid PTY cursor" }
        return parsed
    }

    private fun copyIntoRing(
        bytes: ByteArray,
        offset: Int,
        position: Long,
        length: Int,
    ) {
        val index = (position % ring.size).toInt()
        val first = minOf(length, ring.size - index)
        bytes.copyInto(ring, index, offset, offset + first)
        bytes.copyInto(ring, 0, offset + first, offset + length)
    }
}
