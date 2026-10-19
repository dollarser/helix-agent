package com.helix.app.voice

import android.os.Debug
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Same public audio for both candidates; logs are measurements, not an accuracy benchmark. */
class AsrModelComparisonDeviceTest {
    @Test
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth")
    fun compareChineseCtcAndBilingualCpu() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        for (model in listOf("bilingual", "ctc")) {
            val loadStart = System.nanoTime()
            val recognizer =
                if (model == "ctc") {
                    OnlineRecognizer(
                        assets,
                        OnlineRecognizerConfig(
                            modelConfig =
                                OnlineModelConfig(
                                    zipformer2Ctc = OnlineZipformer2CtcModelConfig("asr-candidate/model.int8.onnx"),
                                    tokens = "asr-candidate/tokens.txt",
                                    numThreads = 2,
                                ),
                            enableEndpoint = false,
                        ),
                    )
                } else {
                    null
                }
            val baseline = if (model == "bilingual") OfflineAsrFactory(app.assets).open() else null
            val loadMs = (System.nanoTime() - loadStart) / 1000000
            baseline?.close()
            try {
                for (name in listOf(
                    "asr-candidate/test_wavs/0.wav",
                    "asr-candidate/test_wavs/1.wav",
                    "asr-test/0.wav",
                )) {
                    val bytes = assets.open(name).use { it.readBytes() }
                    val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    var offset = 12
                    while (String(bytes, offset, 4, Charsets.US_ASCII) != "data") offset += 8 + data.getInt(offset + 4)
                    val count = data.getInt(offset + 4) / 2
                    val stream = recognizer?.createStream()
                    // A fresh baseline stream for each audio, with cold load measured separately above.
                    val asr = if (recognizer == null) OfflineAsrFactory(app.assets).open() else null
                    val started = System.nanoTime()
                    var text = ""
                    var firstAudioMs = 0
                    var peakPss = Debug.getPss()
                    try {
                        for (position in 0 until count step 1600) {
                            val size = minOf(1600, count - position)
                            val samples = FloatArray(size) { data.getShort(offset + 8 + (position + it) * 2) / 32768f }
                            if (recognizer != null && stream != null) {
                                stream.acceptWaveform(samples, 16000)
                                while (recognizer.isReady(stream)) recognizer.decode(stream)
                                text = recognizer.getResult(stream).text
                            } else {
                                text = requireNotNull(asr).accept(samples)
                            }
                            if (text.isNotBlank() && firstAudioMs == 0) firstAudioMs = (position + size) / 16
                            peakPss = maxOf(peakPss, Debug.getPss())
                        }
                        if (recognizer != null && stream != null) {
                            stream.acceptWaveform(FloatArray(8000), 16000)
                            stream.inputFinished()
                            while (recognizer.isReady(stream)) recognizer.decode(stream)
                            text = recognizer.getResult(stream).text
                        } else {
                            text = requireNotNull(asr).finish()
                        }
                        val elapsed = (System.nanoTime() - started) / 1000000
                        Log.i(
                            "HelixAsrEvidence",
                            "model=$model file=$name loadMs=$loadMs decodeMs=$elapsed audioMs=${count / 16} " +
                                "firstAudioMs=$firstAudioMs peakProcessPssKb=$peakPss text=$text",
                        )
                        assertTrue(text.isNotBlank())
                    } finally {
                        stream?.release()
                        asr?.close()
                    }
                }
            } finally {
                recognizer?.release()
            }
        }
    }
}
