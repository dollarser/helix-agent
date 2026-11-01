package com.helix.app.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/** Completed PCM utterances, not a recognition result or permission to send a message. */
interface SpeechSegments : AutoCloseable {
    fun accept(samples: FloatArray): List<FloatArray>

    fun finish(): List<FloatArray>
}

/** Existing pinned sherpa/ORT runtime; the fixed MIT VAD model is bundled, never downloaded on capture. */
class SileroSpeechSegments(
    context: Context,
) : SpeechSegments {
    private val vad =
        Vad(
            context.assets,
            VadModelConfig(
                sileroVadModelConfig =
                    SileroVadModelConfig(
                        model = "voice-vad/silero-v5.onnx",
                        threshold = 0.5f,
                        minSilenceDuration = 0.8f,
                        minSpeechDuration = 0.15f,
                        windowSize = 512,
                        maxSpeechDuration = 8f,
                    ),
                numThreads = 1,
                sampleRate = 16000,
            ),
        )

    override fun accept(samples: FloatArray): List<FloatArray> {
        vad.acceptWaveform(samples)
        return completed()
    }

    override fun finish(): List<FloatArray> {
        vad.flush()
        return completed()
    }

    private fun completed(): List<FloatArray> =
        buildList {
            while (!vad.empty()) {
                val samples = vad.front().samples
                require(samples.size in 1..16000 * 12) { "VAD_SEGMENT_LIMIT" }
                check(size < 4) { "VAD_QUEUE_LIMIT" }
                add(samples)
                vad.pop()
            }
        }

    override fun close() {
        vad.release()
    }
}
