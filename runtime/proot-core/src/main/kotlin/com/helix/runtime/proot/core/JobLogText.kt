package com.helix.runtime.proot.core

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** Incremental UTF-8 per stream; decoded text occupies at most 256 KiB of UTF-16. */
class JobLogText {
    private val decoders =
        List(2) {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(
                    CodingErrorAction.REPLACE,
                ).onUnmappableCharacter(CodingErrorAction.REPLACE)
        }
    private val pending = Array(2) { byteArrayOf() }
    private val text = List(2) { StringBuilder() }
    private var chars = 0
    private var full = false
    var truncated = false
        private set
    val stdout: String get() = text[0].toString()
    val stderr: String get() = text[1].toString()

    fun append(page: JobLogPage) {
        require(page.stream in 1..2 && page.bytes.size <= JobLogSpool.CHUNK_BYTES)
        truncated = truncated || page.truncated
        decode(page.stream - 1, page.bytes, false)
        if (page.eof) {
            decode(0, byteArrayOf(), true)
            decode(1, byteArrayOf(), true)
        }
    }

    private fun decode(
        index: Int,
        bytes: ByteArray,
        end: Boolean,
    ) {
        val input = ByteBuffer.wrap(pending[index] + bytes)
        val output = CharBuffer.allocate(JobLogSpool.CHUNK_BYTES + 4)
        decoders[index].decode(input, output, end)
        pending[index] = ByteArray(input.remaining()).also { input.get(it) }
        output.flip()
        var count = if (full) 0 else minOf(output.remaining(), 128 * 1024 - chars)
        if (count < output.remaining()) {
            truncated = true
            full = true
            if (count > 0 && output.get(count - 1).isHighSurrogate()) count--
        }
        repeat(count) { text[index].append(output.get()) }
        chars += count
    }
}
