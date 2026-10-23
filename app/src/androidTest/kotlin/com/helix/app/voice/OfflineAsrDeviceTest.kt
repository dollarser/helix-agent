package com.helix.app.voice

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Synthetic/public fixtures only. Never opens the microphone. */
@RunWith(AndroidJUnit4::class)
class OfflineAsrDeviceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun publicBilingualFixtureStreamsAndFinalizesOffline() {
        installOfflineAsrFixture(context)
        val bytes =
            androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation()
                .context.assets
                .open(
                    "asr-test/0.wav",
                ).use {
                    it.readBytes()
                }
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(16000, data.getInt(24))
        var offset = 12
        while (String(bytes, offset, 4, Charsets.US_ASCII) != "data") offset += 8 + data.getInt(offset + 4)
        val count = data.getInt(offset + 4) / 2
        val samples = FloatArray(count) { data.getShort(offset + 8 + it * 2) / 32768f }
        val start = System.nanoTime()
        val asr = OfflineAsrFactory(context).open()
        val loaded = System.nanoTime()
        var first = 0L
        var partials = 0
        var previous = ""
        try {
            samples.asList().chunked(1600).forEach { chunk ->
                val text = asr.accept(chunk.toFloatArray())
                if (text.isNotBlank() && first == 0L) first = System.nanoTime()
                if (text != previous) partials++
                previous = text
            }
            val text = asr.finish()
            Log.i(
                "HelixAsrEvidence",
                "loadMs=${(loaded - start) / 1000000} firstDecodeMs=${(first - loaded) / 1000000} " +
                    "totalMs=${(System.nanoTime() - start) / 1000000} audioMs=${count / 16} " +
                    "partials=$partials text=$text",
            )
            assertTrue(text, text.contains("昨天"))
            assertTrue(text, text.contains("星期三"))
            assertTrue(partials > 1)
        } finally {
            asr.close()
            asr.close() // Native release and its use lease are idempotent.
        }
    }

    @Test fun cancellationDuringModelLoadNeverOpensAudioOrPublishesLateResult() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val audio = AtomicBoolean(false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val capture =
            VoiceCapture(
                context,
                scope,
                StreamingAsrFactory {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    object : StreamingAsr {
                        override fun accept(samples: FloatArray) = "迟到"

                        override fun finish() = "迟到"

                        override fun close() {
                            closed.countDown()
                        }
                    }
                },
                {
                    audio.set(true)
                    error("must not capture")
                },
            )
        try {
            capture.start()
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            capture.cancel()
            release.countDown()
            assertTrue(closed.await(10, TimeUnit.SECONDS))
            assertFalse(audio.get())
            assertEquals(VoiceCapture.Phase.CANCELLED, capture.state.value.phase)
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

    @Test fun missingModelIsExplicitAndDoesNotOpenAudio() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val capture =
            VoiceCapture(context, scope, StreamingAsrFactory { error("missing model") }, { error("must not capture") })
        try {
            capture.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (capture.state.value.phase == VoiceCapture.Phase.LOADING &&
                System.nanoTime() < deadline
            ) {
                Thread.yield()
            }
            assertEquals(VoiceCapture.Phase.FAILED, capture.state.value.phase)
            assertEquals(VoiceCapture.Failure.MODEL, capture.state.value.failure)
        } finally {
            scope.cancel()
        }
    }
}
