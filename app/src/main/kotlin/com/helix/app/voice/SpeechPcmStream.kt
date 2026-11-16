package com.helix.app.voice

/** PCM16 little-endian mono; network chunks need not align to sample boundaries. */
internal class SpeechPcmStream(
    private val consume: (FloatArray, Int) -> Unit,
) {
    private var lowByte = -1
    private var frames = 0L

    fun accept(bytes: ByteArray) {
        val output = FloatArray(1600)
        var count = 0
        for (byte in bytes) {
            if (lowByte < 0) {
                lowByte = byte.toInt() and 255
            } else {
                output[count++] = ((byte.toInt() shl 8) or lowByte).toShort() / 32768f
                lowByte = -1
                frames++
                if (count == output.size) {
                    consume(output.copyOf(), 24000)
                    count = 0
                }
            }
        }
        if (count > 0) consume(output.copyOf(count), 24000)
    }

    fun finish() {
        require(lowByte < 0 && frames > 0) { "PCM_RESPONSE_INVALID" }
    }
}
