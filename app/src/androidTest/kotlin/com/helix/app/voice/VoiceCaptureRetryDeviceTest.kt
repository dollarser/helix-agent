package com.helix.app.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Delayed native cleanup is simulated; no microphone or service is opened. */
class VoiceCaptureRetryDeviceTest {
    @Test fun repeatedRetryWaitsForPriorCleanupAndReopensOnlyOnce() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val closing = CountDownLatch(1)
            val release = CountDownLatch(1)
            val cleaned = AtomicBoolean(false)
            val opens = AtomicInteger()
            val context = ApplicationProvider.getApplicationContext<Context>()
            val factory =
                StreamingAsrFactory {
                    val first = opens.incrementAndGet() == 1
                    if (!first) check(cleaned.get())
                    object : StreamingAsr {
                        override fun accept(samples: FloatArray): String {
                            if (first) error("Synthetic recognition failure")
                            return "合成结果"
                        }

                        override fun finish() = "合成结果"

                        override fun close() {
                            if (first) {
                                closing.countDown()
                                check(release.await(5, TimeUnit.SECONDS))
                                cleaned.set(true)
                            }
                        }
                    }
                }
            val capture =
                VoiceCapture(context, scope, factory) {
                    object : PcmCapture {
                        override fun read(samples: ShortArray) = samples.size

                        override fun stop() = Unit

                        override fun close() = Unit
                    }
                }
            try {
                capture.start()
                assertTrue(closing.await(5, TimeUnit.SECONDS))
                val retry = launch { capture.retry() }
                withTimeout(5000) { while (capture.state.value.phase != VoiceCapture.Phase.LOADING) delay(10) }
                capture.retry()
                assertEquals(1, opens.get())
                release.countDown()
                retry.join()
                withTimeout(5000) { while (capture.state.value.phase != VoiceCapture.Phase.REVIEW) delay(10) }
                assertEquals(2, opens.get())
                assertEquals("合成结果", capture.state.value.text)
            } finally {
                release.countDown()
                scope.cancel()
            }
        }
}
