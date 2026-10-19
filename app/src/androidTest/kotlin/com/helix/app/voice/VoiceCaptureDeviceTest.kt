package com.helix.app.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Controller failure/cancellation/limit contracts using inert PCM, never the microphone. */
class VoiceCaptureDeviceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun maximumCaptureFinishesOnceAndReleasesBothResources() {
        val accepts = AtomicInteger()
        val finishes = AtomicInteger()
        val stops = AtomicInteger()
        val closed = CountDownLatch(2)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val capture =
            VoiceCapture(
                context,
                scope,
                StreamingAsrFactory {
                    object : StreamingAsr {
                        override fun accept(samples: FloatArray): String {
                            accepts.incrementAndGet()
                            return "预览"
                        }

                        override fun finish(): String {
                            finishes.incrementAndGet()
                            return "完整文字"
                        }

                        override fun close() {
                            closed.countDown()
                        }
                    }
                },
                {
                    object : PcmCapture {
                        override fun read(samples: ShortArray) = samples.size

                        override fun stop() {
                            stops.incrementAndGet()
                        }

                        override fun close() {
                            closed.countDown()
                        }
                    }
                },
            )
        try {
            capture.start()
            assertTrue(closed.await(10, TimeUnit.SECONDS))
            assertEquals(1200, accepts.get())
            assertEquals(1, finishes.get())
            assertEquals(1, stops.get())
            assertEquals(VoiceCapture.Phase.REVIEW, capture.state.value.phase)
            assertEquals("完整文字", capture.state.value.text)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun lateDecodeCannotPublishAfterInterruptionAndReleasesSource() {
        val decoding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(2)
        var finished = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val capture =
            VoiceCapture(
                context,
                scope,
                StreamingAsrFactory {
                    object : StreamingAsr {
                        override fun accept(samples: FloatArray): String {
                            decoding.countDown()
                            check(release.await(5, TimeUnit.SECONDS))
                            return "迟到识别"
                        }

                        override fun finish(): String {
                            finished = true
                            return "不应出现"
                        }

                        override fun close() {
                            closed.countDown()
                        }
                    }
                },
                {
                    object : PcmCapture {
                        override fun read(samples: ShortArray) = samples.size

                        override fun stop() = Unit

                        override fun close() {
                            closed.countDown()
                        }
                    }
                },
            )
        try {
            capture.start()
            assertTrue(decoding.await(5, TimeUnit.SECONDS))
            capture.cancel(VoiceCapture.Failure.INTERRUPTED)
            release.countDown()
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            assertEquals(VoiceCapture.Phase.FAILED, capture.state.value.phase)
            assertEquals(VoiceCapture.Failure.INTERRUPTED, capture.state.value.failure)
            assertEquals("", capture.state.value.text)
            assertFalse(finished)
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

    @Test
    fun failedAudioReadIsExplicitAndClosesEngine() {
        val closed = CountDownLatch(2)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val capture =
            VoiceCapture(
                context,
                scope,
                StreamingAsrFactory {
                    object : StreamingAsr {
                        override fun accept(samples: FloatArray) = error("must not decode")

                        override fun finish() = error("must not finish")

                        override fun close() {
                            closed.countDown()
                        }
                    }
                },
                {
                    object : PcmCapture {
                        override fun read(samples: ShortArray) = -3

                        override fun stop() = Unit

                        override fun close() {
                            closed.countDown()
                        }
                    }
                },
            )
        try {
            capture.start()
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            assertEquals(VoiceCapture.Phase.FAILED, capture.state.value.phase)
            assertEquals(VoiceCapture.Failure.AUDIO, capture.state.value.failure)
        } finally {
            scope.cancel()
        }
    }
}
