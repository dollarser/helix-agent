package com.helix.app.ui

import android.media.AudioManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.voice.LocalVoiceModel
import com.helix.app.voice.MeloSpeechEngine
import com.helix.app.voice.ReadAloudPhase
import com.helix.app.voice.ReadAloudService
import com.helix.app.voice.SpeechAudio
import com.helix.app.voice.SpeechEngine
import com.helix.app.voice.SpeechSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Real model -> bounded player -> real zero-volume AudioTrack, with foreground focus and explicit UI stop. */
class LocalReadAloudPlaybackDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val created = AtomicInteger()
    private val closed = AtomicInteger()
    private val written = AtomicInteger()
    private val firstWrite = AtomicLong()

    @Test fun actualLocalSynthesisReachesSilentTrackAndExplicitStopReleasesEngine() {
        check(
            android.os.Build.MODEL
                .contains("sdk_gphone"),
        )
        val manager = context.getSystemService(AudioManager::class.java)
        val volume = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val muted = manager.isStreamMute(AudioManager.STREAM_MUSIC)
        fixture { service ->
            compose.setContent {
                MaterialTheme {
                    ReadAloudLifecycle(service, "synthetic")
                    ReadAloudAction("real", "这是一段用于静音端到端播放与取消验证的合成文字内容。", service)
                }
            }
            val began = android.os.SystemClock.elapsedRealtime()
            compose.onNodeWithTag("chat-read-real").performClick()
            compose.waitUntil(60_000) { written.get() > 0 && service.state.value.phase == ReadAloudPhase.SPEAKING }
            val stopAt = android.os.SystemClock.elapsedRealtime()
            compose.onNodeWithTag("chat-read-real").performClick()
            compose.waitUntil(60_000) { service.state.value.phase == ReadAloudPhase.IDLE && closed.get() == 1 }
            android.util.Log.i(
                "HelixTtsLatency",
                "playerFirstWriteMs=${firstWrite.get() - began} " +
                    "stopToEngineClosedMs=${android.os.SystemClock.elapsedRealtime() - stopAt} volumeZero=true",
            )
            assertEquals(1, created.get())
            assertTrue(written.get() > 0)
        }
        assertEquals(volume, manager.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertEquals(muted, manager.isStreamMute(AudioManager.STREAM_MUSIC))
    }

    private fun fixture(block: (ReadAloudService) -> Unit) {
        val root = File(context.cacheDir, "tts-playback-${UUID.randomUUID()}")
        val directory = File(root, "melo-int8")
        check(directory.mkdirs())
        val model = LocalVoiceModel(context, root)
        model.parts.forEach { part ->
            instrumentation.context.assets.open("tts-test/${part.name}").use { input ->
                File(directory, part.name).outputStream().use(input::copyTo)
            }
        }
        val audio = SpeechAudio(context, volume = 0f)
        val service =
            ReadAloudService(model, { dir ->
                created.incrementAndGet()
                val engine = MeloSpeechEngine(dir)
                object : SpeechEngine by engine {
                    override fun close() {
                        engine.close()
                        closed.incrementAndGet()
                    }
                }
            }, audio::focus, { rate -> realSink(audio, rate) })
        try {
            block(service)
        } finally {
            compose.runOnUiThread(service::close)
            compose.waitUntil(60_000) { closed.get() == created.get() }
            root.deleteRecursively()
        }
    }

    private fun realSink(
        audio: SpeechAudio,
        rate: Int,
    ): SpeechSink {
        assertEquals(44_100, rate)
        val target = audio.sink(rate)
        return object : SpeechSink by target {
            override fun write(
                samples: FloatArray,
                keepGoing: () -> Boolean,
            ) {
                val count = minOf(1024, samples.size)
                target.write(samples.copyOfRange(0, count), keepGoing)
                firstWrite.compareAndSet(0, android.os.SystemClock.elapsedRealtime())
                written.addAndGet(count)
                if (count < samples.size) target.write(samples.copyOfRange(count, samples.size), keepGoing)
            }
        }
    }
}
