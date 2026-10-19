package com.helix.app.voice

import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

interface SpeechEngine : AutoCloseable {
    fun generate(
        text: String,
        keepGoing: () -> Boolean,
        consume: (FloatArray, Int) -> Unit,
    )
}

class MeloSpeechEngine(
    directory: File,
) : SpeechEngine {
    private val rules =
        listOf("date.fst", "number.fst", "phone.fst")
            .joinToString(",") { File(directory, it).path }
    private val tts =
        OfflineTts(
            config =
                OfflineTtsConfig(
                    model =
                        OfflineTtsModelConfig(
                            vits =
                                OfflineTtsVitsModelConfig(
                                    model = File(directory, "model.int8.onnx").path,
                                    lexicon = File(directory, "lexicon.txt").path,
                                    tokens = File(directory, "tokens.txt").path,
                                ),
                            numThreads = 2,
                            debug = false,
                        ),
                    ruleFsts = rules,
                    maxNumSentences = 1,
                ),
        )

    @Suppress("TooGenericExceptionCaught") // Never propagate a Java callback exception across JNI.
    override fun generate(text: String, keepGoing: () -> Boolean, consume: (FloatArray, Int) -> Unit) {
        var failure: Exception? = null
        // JNI looks up invoke(float[]) -> Integer; an indy lambda only exposes invoke(Object).
        val callback =
            object : (FloatArray) -> Int {
                override fun invoke(samples: FloatArray): Int =
                    if (keepGoing()) {
                        try {
                            consume(samples, tts.sampleRate())
                            if (keepGoing()) 1 else 0
                        } catch (error: Exception) {
                            failure = error
                            0
                        }
                    } else {
                        0
                    }
            }
        val audio = tts.generateWithCallback(text, sid = 0, speed = 1f, callback = callback)
        failure?.let { throw it }
        check(!keepGoing() || audio.samples.isNotEmpty()) { "NO_SYNTHESIZED_AUDIO" }
    }

    override fun close() = tts.release()
}
