package com.helix.app.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Narrow PCM16 mono RIFF/WAVE codec for the confirmed HTTP service contract. */
object SpeechWav {
    fun encode(
        pcm16: ByteArray,
        rate: Int,
    ): ByteArray {
        require(pcm16.size <= 12_000_000 && pcm16.size % 2 == 0 && rate in 8000..48000)
        return ByteBuffer
            .allocate(44 + pcm16.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put("RIFF".toByteArray())
                putInt(36 + pcm16.size)
                put("WAVEfmt ".toByteArray())
                putInt(16)
                putShort(1)
                putShort(1)
                putInt(rate)
                putInt(rate * 2)
                putShort(2)
                putShort(16)
                put("data".toByteArray())
                putInt(pcm16.size)
                put(pcm16)
            }.array()
    }

    fun consume(
        wav: ByteArray,
        consume: (FloatArray, Int) -> Unit,
    ) {
        require(wav.size in 44..12_000_000)
        val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        require(tag(wav, 0) == "RIFF" && tag(wav, 8) == "WAVE") { "WAV_HEADER_INVALID" }
        require(buffer.getInt(4).toLong() + 8 == wav.size.toLong()) { "WAV_SIZE_INVALID" }
        var offset = 12
        var rate: Int? = null
        var dataSeen = false
        while (offset + 8 <= wav.size) {
            val size = buffer.getInt(offset + 4)
            require(size >= 0 && offset.toLong() + 8 + size <= wav.size)
            when (tag(wav, offset)) {
                "fmt " -> {
                    require(rate == null && size >= 16)
                    rate = readRate(buffer, offset)
                }

                "data" -> {
                    val sampleRate = requireNotNull(rate)
                    require(!dataSeen && size > 0 && size % 2 == 0 && size <= sampleRate * 120 * 2)
                    dataSeen = true
                    var cursor = offset + 8
                    val end = cursor + size
                    while (cursor < end) {
                        val frames = minOf(1600, (end - cursor) / 2)
                        val samples = FloatArray(frames) { buffer.getShort(cursor + it * 2) / 32768f }
                        consume(samples, sampleRate)
                        cursor += frames * 2
                    }
                }
            }
            offset += 8 + size + size % 2
        }
        require(dataSeen && offset == wav.size) { "WAV_DATA_INVALID" }
    }

    private fun readRate(
        buffer: ByteBuffer,
        offset: Int,
    ): Int {
        val rate = buffer.getInt(offset + 12)
        require(buffer.getShort(offset + 8).toInt() == 1 && buffer.getShort(offset + 10).toInt() == 1)
        require(rate in 8000..48000 && buffer.getInt(offset + 16) == rate * 2)
        require(buffer.getShort(offset + 20).toInt() == 2 && buffer.getShort(offset + 22).toInt() == 16)
        return rate
    }

    private fun tag(
        bytes: ByteArray,
        offset: Int,
    ) = String(bytes, offset, 4, Charsets.US_ASCII)
}
