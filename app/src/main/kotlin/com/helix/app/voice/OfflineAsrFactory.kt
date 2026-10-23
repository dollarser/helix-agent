package com.helix.app.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/** Installed, verified CPU model; opening never downloads or uses a system speech service. */
class OfflineAsrFactory(
    private val model: LocalSpeechModel,
) : StreamingAsrFactory {
    constructor(context: Context) : this(LocalSpeechModel.asr(context))

    @Suppress("TooGenericExceptionCaught") // Resource cleanup; the original failure is rethrown.
    override fun open(): StreamingAsr {
        val directory = kotlinx.coroutines.runBlocking { model.ready() }
        checkNotNull(directory) { "ASR_MODEL_NOT_INSTALLED" }
        val root = directory.path + "/"
        val recognizer =
            OnlineRecognizer(
                config =
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
