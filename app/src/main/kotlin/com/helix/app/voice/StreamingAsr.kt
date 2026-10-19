package com.helix.app.voice

/** PCM is mono 16 kHz, normalized to [-1, 1]. Implementations must declare any network use. */
interface StreamingAsr : AutoCloseable {
    fun accept(samples: FloatArray): String

    fun finish(): String
}

/** No transport choice or credentials can be inferred from chat model settings. */
fun interface StreamingAsrFactory {
    fun open(): StreamingAsr
}
