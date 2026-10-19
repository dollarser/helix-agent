package com.helix.app.voice

import ai.moonshine.voice.JNI
import ai.moonshine.voice.Transcriber
import ai.moonshine.voice.TranscriberOption
import android.os.Debug
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineWenetCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Opt-in public PCM benchmark. Never accesses AudioRecord, accounts or chat state. */
class SpeechBenchmarkDeviceTest {
    private interface Engine : AutoCloseable {
        fun accept(samples: FloatArray): String

        fun finish(): String
    }

    private fun engine(
        id: String,
        root: File,
    ): Engine =
        when (id) {
            "vosk" -> voskEngine(root)
            "moonshine" -> moonshineEngine(root)
            "sensevoice", "whisper", "wenet" -> offlineEngine(id, root)
            else -> onlineEngine(id, root)
        }

    private fun voskEngine(root: File): Engine {
        val model = Model(File(root, "models/vosk").absolutePath)
        val recognizer = Recognizer(model, 16000f)
        val completed = StringBuilder()
        return object : Engine {
            override fun accept(samples: FloatArray): String {
                val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                samples.forEach { bytes.putShort((it * 32768).toInt().coerceIn(-32768, 32767).toShort()) }
                if (recognizer.acceptWaveForm(bytes.array(), bytes.capacity())) {
                    completed.append(JSONObject(recognizer.result).optString("text")).append(" ")
                }
                return completed.toString() + JSONObject(recognizer.partialResult).optString("partial")
            }

            override fun finish() = completed.toString() + JSONObject(recognizer.finalResult).optString("text")

            override fun close() {
                recognizer.close()
                model.close()
            }
        }
    }

    private fun moonshineEngine(root: File): Engine {
        val transcriber = Transcriber(listOf(TranscriberOption("max_tokens_per_second", "13")))
        transcriber.loadFromFiles(
            File(root, "models/moonshine").absolutePath,
            JNI.MOONSHINE_MODEL_ARCH_TINY_STREAMING,
        )
        // The official native transcript getter avoids rebuilding text from UI events.
        val stream = transcriber.createStream()
        transcriber.startStream(stream)
        return object : Engine {
            private var text = ""
            private var pending = 0

            override fun accept(samples: FloatArray): String {
                val code =
                    JNI.moonshineAddAudioToStream(
                        transcriberHandle(transcriber),
                        stream,
                        samples,
                        16000,
                        0,
                    )
                check(code == 0)
                pending += samples.size
                if (pending < 8000) return text
                pending = 0
                val t = JNI.moonshineTranscribeStream(transcriberHandle(transcriber), stream, 0)
                text = requireNotNull(t) { "Moonshine transcription failed" }.text()
                return text
            }

            override fun finish(): String {
                check(JNI.moonshineStopStream(transcriberHandle(transcriber), stream) == 0)
                val t =
                    JNI.moonshineTranscribeStream(
                        transcriberHandle(transcriber),
                        stream,
                        JNI.MOONSHINE_FLAG_FORCE_UPDATE,
                    )
                return requireNotNull(t) { "Moonshine final transcription failed" }.text()
            }

            override fun close() {
                transcriber.freeStream(stream)
                transcriber.close()
            }
        }
    }

    private fun offlineEngine(
        id: String,
        root: File,
    ): Engine {
        fun path(name: String) = File(root, "models/$id/$name").absolutePath
        val config = OfflineModelConfig(numThreads = 2, tokens = path("tokens.txt"))
        if (id == "sensevoice") {
            config.senseVoice = OfflineSenseVoiceModelConfig(path("model.int8.onnx"), "auto", false)
        } else if (id == "wenet") {
            config.wenetCtc = OfflineWenetCtcModelConfig(path("model.int8.onnx"))
        } else {
            config.whisper =
                OfflineWhisperModelConfig(
                    path("tiny-encoder.int8.onnx"),
                    path("tiny-decoder.int8.onnx"),
                    "",
                )
        }
        val recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = config))
        val stream = recognizer.createStream()
        return object : Engine {
            override fun accept(samples: FloatArray): String {
                stream.acceptWaveform(samples, 16000)
                return ""
            }

            override fun finish(): String {
                recognizer.decode(stream)
                return recognizer.getResult(stream).text
            }

            override fun close() {
                stream.release()
                recognizer.release()
            }
        }
    }

    private fun onlineEngine(
        id: String,
        root: File,
    ): Engine {
        fun path(name: String) = File(root, "models/$id/$name").absolutePath
        val config = OnlineModelConfig(numThreads = 2, tokens = path("tokens.txt"))
        when (id) {
            "ctc" -> {
                config.zipformer2Ctc = OnlineZipformer2CtcModelConfig(path("model.int8.onnx"))
            }

            "paraformer" -> {
                config.paraformer =
                    OnlineParaformerModelConfig(
                        path("encoder.int8.onnx"),
                        path("decoder.int8.onnx"),
                    )
            }

            else -> {
                config.transducer =
                    OnlineTransducerModelConfig(
                        path("encoder.int8.onnx"),
                        path("decoder.onnx"),
                        path("joiner.int8.onnx"),
                    )
            }
        }
        val recognizer = OnlineRecognizer(config = OnlineRecognizerConfig(modelConfig = config, enableEndpoint = false))
        val stream = recognizer.createStream()
        return object : Engine {
            private fun result(): String {
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                return recognizer.getResult(stream).text
            }

            override fun accept(samples: FloatArray): String {
                stream.acceptWaveform(samples, 16000)
                return result()
            }

            override fun finish(): String {
                stream.acceptWaveform(FloatArray(8000), 16000)
                stream.inputFinished()
                return result()
            }

            override fun close() {
                stream.release()
                recognizer.release()
            }
        }
    }

    private fun transcriberHandle(transcriber: Transcriber): Int {
        val field = Transcriber::class.java.getDeclaredField("transcriberHandle")
        field.isAccessible = true
        return field.getInt(transcriber)
    }

    @Test(timeout = 900000)
    fun publicCorpus() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val id = requireNotNull(args.getString("model"))
        val root = File(instrumentation.targetContext.filesDir, "asr-benchmark")
        val corpus = JSONArray(File(root, "corpus.json").readText())
        val output = File(root, "results-$id.jsonl")
        output.writeText("")
        val limit = args.getString("limit")?.toInt() ?: corpus.length()
        for (i in 0 until minOf(limit, corpus.length())) {
            val item = corpus.getJSONObject(i)
            benchmarkClip(id, root, item, output, args.getString("paced") == "true")
        }
        check(output.readLines().size == minOf(limit, corpus.length()))
    }

    private fun benchmarkClip(
        id: String,
        root: File,
        item: JSONObject,
        output: File,
        paced: Boolean,
    ) {
        val bytes = File(root, item.getString("file")).readBytes()
        val pcm = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val samples = FloatArray(bytes.size / 2) { pcm.short / 32768f }
        val startLoad = System.nanoTime()
        val engine = engine(id, root)
        val loadMs = (System.nanoTime() - startLoad) / 1e6
        var firstTextMs = -1.0
        var text = ""
        var computeNs = 0L
        val startPss = Debug.getPss()
        val started = System.nanoTime()
        try {
            for (position in samples.indices step 1600) {
                val end = minOf(samples.size, position + 1600)
                waitForFrame(started, end, paced)
                val chunk = samples.copyOfRange(position, end)
                val began = System.nanoTime()
                text = engine.accept(chunk)
                computeNs += System.nanoTime() - began
                if (firstTextMs < 0 && text.isNotBlank()) firstTextMs = (System.nanoTime() - started) / 1e6
            }
            val stopped = System.nanoTime()
            text = engine.finish()
            val finished = System.nanoTime()
            computeNs += finished - stopped
            val row =
                JSONObject()
                    .put("model", id)
                    .put("sample", item.getString("id"))
                    .put("group", item.getString("group"))
                    .put("reference", item.getString("reference"))
                    .put("text", text)
                    .put("audioMs", samples.size / 16.0)
                    .put("loadMs", loadMs)
                    .put("computeMs", computeNs / 1e6)
                    .put("elapsedMs", (finished - started) / 1e6)
                    .put("finishMs", (finished - stopped) / 1e6)
                    .put("firstTextMs", firstTextMs)
                    .put("sampledProcessPssKb", maxOf(startPss, Debug.getPss()))
                    .put("paced", paced)
            output.appendText(row.toString() + "\n")
            android.util.Log.i("HelixAsrBenchmark", row.toString())
        } finally {
            engine.close()
        }
    }

    private fun waitForFrame(
        started: Long,
        end: Int,
        paced: Boolean,
    ) {
        if (!paced) return
        val dueNs = started + end.toLong() * 1000000000 / 16000
        val waitMs = (dueNs - System.nanoTime()) / 1000000
        if (waitMs > 0) SystemClock.sleep(waitMs)
    }
}
