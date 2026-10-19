package com.helix.app.voice

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.model.ReadAloudText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Paired first-PCM measurements on one engine; files/quiet callbacks only, never speaker output. */
class ReadAloudLatencyDeviceTest {
    @Test fun compareOpeningChunkAndMeasureNativeCancellationBoundary(): Unit =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val root = File(context.cacheDir, "tts-latency-${UUID.randomUUID()}")
            val directory = File(root, "melo-int8")
            val model = LocalVoiceModel(context, root)
            try {
                check(directory.mkdirs())
                model.parts.forEach { part ->
                    instrumentation.context.assets.open("tts-test/${part.name}").use { input ->
                        File(directory, part.name).outputStream().use(input::copyTo)
                    }
                }
                assertNotNull(model.ready())
                val started = SystemClock.elapsedRealtime()
                MeloSpeechEngine(directory).use { voice ->
                    val loadMs = SystemClock.elapsedRealtime() - started
                    val cases =
                        listOf(
                            "date" to "今天是2026年10月19日，预算137元。银行行长正在检查重庆的项目。",
                            "long" to "这是一段没有提前句号的合成说明文字用于核对首段音频是否必须等待整句推理完成并且验证后续播放不会积累全部内容。",
                        )
                    cases.forEach { (case, text) ->
                        val previous = text.take(64)
                        val opening = ReadAloudText.chunks(text).first()
                        assertTrue(opening.length < previous.length)
                        listOf(
                            "previous" to previous,
                            "current" to opening,
                            "current" to opening,
                            "previous" to previous,
                        ).forEachIndexed { trial, (strategy, input) ->
                            measure(voice, input, "case=$case trial=$trial strategy=$strategy", loadMs)
                        }
                    }
                }
            } finally {
                root.deleteRecursively()
            }
        }

    private fun measure(
        voice: SpeechEngine,
        input: String,
        label: String,
        loadMs: Long,
    ) {
        var active = true
        var callbacks = 0
        var first = 0L
        val begin = SystemClock.elapsedRealtime()
        voice.generate(input, { active }) { samples, _ ->
            assertTrue(samples.any { kotlin.math.abs(it) > 0.0001f })
            first = SystemClock.elapsedRealtime()
            callbacks++
            active = false
        }
        val ended = SystemClock.elapsedRealtime()
        assertEquals(1, callbacks)
        android.util.Log.i(
            "HelixTtsLatency",
            "$label chars=${input.length} loadMs=$loadMs firstMs=${first - begin} cancelExitMs=${ended - first}",
        )
    }
}
