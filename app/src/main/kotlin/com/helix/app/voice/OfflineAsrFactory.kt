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

    @Suppress("TooGenericExceptionCaught") // Release the native-use lease on any load failure; never return success.
    override fun open(): StreamingAsr {
        val lease = kotlinx.coroutines.runBlocking { model.acquireAsrUse() }
        return try {
            openInstalled(lease)
        } catch (failure: Throwable) {
            lease.close()
            throw failure
        }
    }

    @Suppress("TooGenericExceptionCaught") // Native stream construction failure releases its recognizer and propagates.
    private fun openInstalled(lease: AutoCloseable): StreamingAsr {
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
                                        encoder = root + "encoder.fp16.onnx",
                                        decoder = root + "decoder.onnx",
                                        joiner = root + "joiner.fp16.onnx",
                                    ),
                                tokens = root + "tokens.txt",
                                numThreads = 2,
                                modelType = "zipformer2",
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
            private val closed =
                java.util.concurrent.atomic
                    .AtomicBoolean()

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
                if (!closed.compareAndSet(false, true)) return
                try {
                    stream.release()
                } finally {
                    try {
                        recognizer.release()
                    } finally {
                        lease.close()
                    }
                }
            }
        }
    }
}
