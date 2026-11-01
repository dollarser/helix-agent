package com.helix.app.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Deliberately delayed synthesis cleanup, inert PCM and fake ASR; no microphone/audio/network. */
class VoicePlaybackHandoffDeviceTest {
    @Test fun preparationFailureAndRetryNeverOpenAsr(): Unit =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            require(context.packageName.endsWith(".asrvalidation"))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val attempts = AtomicInteger()
            val opens = AtomicInteger()
            val capture =
                VoiceCapture(
                    context,
                    scope,
                    StreamingAsrFactory {
                        opens.incrementAndGet()
                        error("ASR must remain unopened")
                    },
                    { error("Recorder must remain unopened") },
                )
            try {
                capture.start {
                    attempts.incrementAndGet()
                    error("Synthetic handoff failure")
                }
                withTimeout(5000) { while (capture.state.value.phase != VoiceCapture.Phase.FAILED) delay(10) }
                assertEquals(VoiceCapture.Failure.BUSY, capture.state.value.failure)
                capture.retry()
                withTimeout(5000) { while (capture.state.value.phase != VoiceCapture.Phase.FAILED) delay(10) }
                assertEquals(2, attempts.get())
                assertEquals(0, opens.get())
            } finally {
                capture.cancel()
                scope.cancel()
            }
        }

    @Test fun inputWaitsForActualPlaybackExit() = verifyHandoff(cancel = false)

    @Test fun cancellingWhilePlaybackStopsNeverOpensRecorder() = verifyHandoff(cancel = true)

    @Suppress("LongMethod") // One owned synthesis/input fixture with unconditional native-release simulation.
    private fun verifyHandoff(cancel: Boolean): Unit =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            require(context.packageName.endsWith(".asrvalidation"))
            val root = File(context.cacheDir, "voice-handoff-${UUID.randomUUID()}")
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val closed = AtomicBoolean(false)
            val focusClosed = AtomicBoolean(false)
            val opens = AtomicInteger()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val playback =
                ReadAloudService(
                    LocalSpeechModel(context, root),
                    { error("No native model") },
                    { AutoCloseable { focusClosed.set(true) } },
                    { error("No audio output") },
                    cloudEngine = {
                        object : InterruptibleSpeechEngine {
                            override fun generate(
                                text: String,
                                keepGoing: () -> Boolean,
                                consume: (FloatArray, Int) -> Unit,
                            ) {
                                entered.countDown()
                                check(release.await(5, TimeUnit.SECONDS))
                            }

                            override fun interrupt() = Unit

                            override fun close() {
                                closed.set(true)
                            }
                        }
                    },
                )
            val capture =
                VoiceCapture(
                    context,
                    scope,
                    StreamingAsrFactory {
                        check(closed.get() && focusClosed.get()) { "Playback still owns physical resources" }
                        opens.incrementAndGet()
                        object : StreamingAsr {
                            override fun accept(samples: FloatArray) = "Synthetic draft"

                            override fun finish() = "Synthetic final draft"

                            override fun close() = Unit
                        }
                    },
                    {
                        object : PcmCapture {
                            override fun read(samples: ShortArray): Int {
                                Thread.sleep(2)
                                return samples.size
                            }

                            override fun stop() = Unit

                            override fun close() = Unit
                        }
                    },
                )
            try {
                withContext(Dispatchers.Main) { playback.play("synthetic", "Synthetic speech") }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                capture.start { playback.stopAndAwait() }
                capture.start { error("Repeated start must not overlap") }
                delay(100)
                assertEquals(0, opens.get())
                assertEquals(VoiceCapture.Phase.LOADING, capture.state.value.phase)
                if (cancel) capture.cancel()
                release.countDown()
                withTimeout(5000) {
                    while (!closed.get() || !focusClosed.get()) delay(10)
                    if (!cancel) {
                        while (capture.state.value.phase != VoiceCapture.Phase.LISTENING) delay(10)
                        capture.finish()
                        while (capture.state.value.phase != VoiceCapture.Phase.REVIEW) delay(10)
                    }
                }
                delay(50)
                assertEquals(if (cancel) 0 else 1, opens.get())
                if (cancel) {
                    assertEquals(VoiceCapture.Phase.CANCELLED, capture.state.value.phase)
                } else {
                    assertEquals("Synthetic final draft", capture.state.value.text)
                }
            } finally {
                release.countDown()
                capture.cancel()
                withContext(Dispatchers.Main) {
                    playback.stopAndAwait()
                    playback.close()
                }
                scope.cancel()
                root.deleteRecursively()
            }
        }
}
