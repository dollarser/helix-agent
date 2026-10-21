package com.helix.app.voice

import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Android HTTP/PCM transport checks use only synthetic speech; never open the microphone. */
class CloudSpeechDeviceTest {
    @Test
    fun explicitHttpProfileSynthesizesAndUploadsGeneratedAudio() {
        val args = InstrumentationRegistry.getArguments()
        val tts = args.getString("speechTtsUrl")
        val asr = args.getString("speechAsrUrl")
        assumeTrue("Explicit speech service profile not supplied", tts != null || asr != null)
        require(!tts.isNullOrBlank() && !asr.isNullOrBlank())
        require(args.getString("speechUseBuiltInClientId") == null) { "Legacy public-client profile is unsupported" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val account = (context.applicationContext as HelixApplication).account
        val text = "你好，这是模拟器语音接入测试。"
        val audio = mutableListOf<Float>()
        var rate = 0
        HttpWavTts(
            SpeechServiceSettings(
                useAccountLogin = args.getString("speechUseAccountLogin") == "true",
            ).session(tts, account),
        ).use {
            it.stream(text) { pcm, sampleRate ->
                rate = sampleRate
                audio.addAll(pcm.toList())
            }
        }
        assertTrue(audio.isNotEmpty())
        assertTrue(audio.any { kotlin.math.abs(it) > 0.001f })
        val samples =
            FloatArray((audio.size.toLong() * 16000 / rate).toInt()) { index ->
                val position = index.toDouble() * rate / 16000
                val first = position.toInt().coerceAtMost(audio.lastIndex)
                val second = minOf(first + 1, audio.lastIndex)
                val fraction = (position - first).toFloat()
                audio[first] * (1 - fraction) + audio[second] * fraction
            }
        val result =
            HttpFileAsr(
                SpeechServiceSettings(
                    useAccountLogin = args.getString("speechUseAccountLogin") == "true",
                ).session(asr, account),
            ).use {
                samples.asList().chunked(1600).forEach { part -> assertEquals("", it.send(part.toFloatArray())) }
                it.finish()
            }
        File(context.cacheDir, "cloud-speech-device-result.txt").writeText(
            "input=$text\nrate=$rate\nframes=${audio.size}\nasr=$result\n" +
                "Transport-only assertion; transcription quality is not asserted.\n",
        )
        assertTrue(result.isNotBlank())
    }

    @Test
    fun incrementalCloudOutputStopsPermanentlyForThatAnswer() {
        val requests = mutableListOf<String>()
        var pcm = 0
        val engine =
            CloudSpeechEngine(
                object : CloudTtsConnection {
                    override fun stream(
                        text: String,
                        consume: (FloatArray, Int) -> Unit,
                    ) {
                        requests += text
                        consume(floatArrayOf(0.1f), 16000)
                    }

                    override fun close() = Unit
                },
            )
        val speech =
            LiveReplySpeech(
                object : ReplySpeechOutput {
                    override fun begin(id: String) = Unit

                    override fun append(text: String): Boolean {
                        engine.generate(text, { true }) { samples, _ -> pcm += samples.size }
                        return true
                    }

                    override fun finish() = Unit

                    override fun cancel() = Unit
                },
            )
        val frame = VisibleReplyFrame("one", "turn", "第一句。未完成", VisibleReplyFrame.Phase.STREAMING)
        try {
            speech.start(frame)
            assertEquals(listOf("第一句。"), requests)
            speech.update(frame)
            speech.update(frame.copy(text = "第一句。第二句。"))
            assertEquals(listOf("第一句。", "第二句。"), requests)
            speech.stop()
            speech.update(frame.copy(text = "第一句。第二句。第三句。"))
            speech.update(frame.copy(sessionId = "two"))
            assertEquals(2, pcm)
        } finally {
            engine.close()
        }
    }
}
