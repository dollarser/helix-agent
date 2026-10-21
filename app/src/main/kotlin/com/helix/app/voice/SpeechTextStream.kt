package com.helix.app.voice

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible

/** One input queue for either continuous-input transports or the existing per-chunk engines. */
internal suspend fun generateSpeechStream(
    voice: SpeechEngine,
    chunks: ReceiveChannel<String>,
    keepGoing: () -> Boolean,
    consume: (FloatArray, Int) -> Unit,
) {
    if (voice is ContinuousTextSpeechEngine) {
        runInterruptible {
            voice.generateStream({
                runBlocking {
                    val result = chunks.receiveCatching()
                    result.exceptionOrNull()?.let { throw it }
                    result.getOrNull()
                }
            }, keepGoing, consume)
        }
    } else {
        for (chunk in chunks) {
            currentCoroutineContext().ensureActive()
            if (!keepGoing()) break
            voice.generate(chunk, keepGoing, consume)
        }
    }
}
