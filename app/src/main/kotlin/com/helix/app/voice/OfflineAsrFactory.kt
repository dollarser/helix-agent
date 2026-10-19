package com.helix.app.voice

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/** Fixed bundled CPU model; no system speech service, network, audio file or generated code. */
class OfflineAsrFactory(
    private val assets: AssetManager,
) : StreamingAsrFactory {
    @Suppress("TooGenericExceptionCaught") // Resource cleanup; the original failure is rethrown.
    override fun open(): StreamingAsr {
        // Check assets before JNI: unavailable models produce a recoverable UI error.
        val root = "asr/"
        listOf("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt").forEach {
            assets.open(root + it).use { stream -> check(stream.read() >= 0) }
        }
        val recognizer =
            OnlineRecognizer(
                assets,
                OnlineRecognizerConfig(
                    modelConfig =
                        OnlineModelConfig(
                            transducer =
                                OnlineTransducerModelConfig(
                                    encoder = root + "encoder.int8.onnx",
                                    decoder = root + "decoder.onnx",
                                    joiner = root + "joiner.int8.onnx",
                                ),
                            tokens = root + "tokens.txt",
                            numThreads = 2,
                            modelType = "zipformer",
                        ),
                    enableEndpoint = false,
                ),
            )
        val stream =
            try {
                recognizer.createStream()
            } catch (failure: RuntimeException) {
                recognizer.release()
                throw failure
            }
        return object : StreamingAsr {
            private fun text(): String {
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                return recognizer.getResult(stream).text.trim()
            }

            override fun accept(samples: FloatArray): String {
                stream.acceptWaveform(samples, 16000)
                return text()
            }

            override fun finish(): String {
                stream.acceptWaveform(FloatArray(8000), 16000)
                stream.inputFinished()
                return text()
            }

            override fun close() {
                stream.release()
                recognizer.release()
            }
        }
    }
}
