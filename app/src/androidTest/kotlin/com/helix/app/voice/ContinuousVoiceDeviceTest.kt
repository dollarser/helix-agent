package com.helix.app.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Inert PCM and deterministic transcripts only: no microphone, audio focus, cloud or message sending. */
class ContinuousVoiceDeviceTest {
    @Test fun bundledNativeVadAcceptsSilenceAndReleases() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        require(context.packageName.endsWith(".asrvalidation"))
        SileroSpeechSegments(context).use { vad ->
            repeat(64) { assertTrue(vad.accept(FloatArray(512)).isEmpty()) }
            assertTrue(vad.finish().isEmpty())
        }
    }

    @Test fun pausesCommitInOrderAndReuseLoadedRecognizer(): Unit =
        runBlocking {
            val finish = AtomicBoolean()
            val cancelled = AtomicBoolean()
            val reset = AtomicInteger()
            val closed = AtomicBoolean()
            val sourceClosed = AtomicBoolean()
            val snapshots = java.util.concurrent.CopyOnWriteArrayList<VoiceCapture.State>()
            val owner = Owner(snapshots)
            val asr =
                object : ReusableStreamingAsr {
                    override fun accept(samples: FloatArray) = "partial"

                    override fun finish() = if (reset.get() == 0) "first sentence" else "second sentence"

                    override fun nextUtterance() {
                        reset.incrementAndGet()
                    }

                    override fun close() {
                        closed.set(true)
                    }
                }
            val run =
                ContinuousVoiceRun(
                    StreamingAsrFactory { asr },
                    { recorder(sourceClosed) },
                    {
                        object : SpeechSegments {
                            private var frames = 0

                            override fun accept(samples: FloatArray): List<FloatArray> {
                                frames++
                                if (frames == 20) finish.set(true)
                                return if (frames == 1 || frames == 10) listOf(FloatArray(512)) else emptyList()
                            }

                            override fun finish() = emptyList<FloatArray>()

                            override fun close() = Unit
                        }
                    },
                    owner,
                )
            assertEquals("first sentence second sentence", withTimeout(5000) { run.run(finish, cancelled) })
            assertEquals(2, reset.get())
            assertTrue(closed.get() && sourceClosed.get())
            assertTrue(snapshots.any { it.committedText == "first sentence" })
            assertEquals("first sentence second sentence", snapshots.last().committedText)
        }

    @Test fun cancellationRejectsLateRecognitionAndClosesResources(): Unit =
        runBlocking {
            val cancelled = AtomicBoolean()
            val accepted = AtomicBoolean()
            val release = AtomicBoolean()
            val closed = AtomicBoolean()
            val sourceClosed = AtomicBoolean()
            val snapshots = java.util.concurrent.CopyOnWriteArrayList<VoiceCapture.State>()
            val run =
                ContinuousVoiceRun(
                    StreamingAsrFactory {
                        object : StreamingAsr {
                            override fun accept(samples: FloatArray): String {
                                accepted.set(true)
                                while (!release.get()) Thread.sleep(2)
                                return "late private transcript"
                            }

                            override fun finish() = "must not commit"

                            override fun close() {
                                closed.set(true)
                            }
                        }
                    },
                    { recorder(sourceClosed) },
                    {
                        object : SpeechSegments {
                            private var sent = false

                            override fun accept(samples: FloatArray): List<FloatArray> =
                                if (sent) emptyList() else listOf(FloatArray(512)).also { sent = true }

                            override fun finish() = emptyList<FloatArray>()

                            override fun close() = Unit
                        }
                    },
                    Owner(snapshots),
                )
            val job = launch(kotlinx.coroutines.Dispatchers.Default) { run.run(AtomicBoolean(), cancelled) }
            withTimeout(5000) { while (!accepted.get()) delay(5) }
            cancelled.set(true)
            release.set(true)
            withTimeout(5000) { job.join() }
            assertTrue(closed.get() && sourceClosed.get())
            assertFalse(snapshots.any { it.committedText.isNotEmpty() })
            assertFalse(snapshots.any { it.text.contains("late") })
        }

    @Test fun recognizerFailureNeverOpensMicrophone(): Unit =
        runBlocking {
            val run =
                ContinuousVoiceRun(
                    StreamingAsrFactory { error("Synthetic ASR open failure") },
                    { error("Microphone must remain unopened") },
                    { error("VAD must remain unopened") },
                    Owner(mutableListOf()),
                )
            try {
                run.run(AtomicBoolean(), AtomicBoolean())
                error("Failure must propagate")
            } catch (failure: ContinuousCaptureFailure) {
                assertEquals(VoiceCapture.Failure.MODEL, failure.failure)
            }
        }

    @Test fun fullQueueStopsInsteadOfDroppingAudioAndWaitsForRecognitionExit(): Unit =
        runBlocking {
            val exit = AtomicBoolean()
            val closed = AtomicBoolean()
            val sourceClosed = AtomicBoolean()
            val run =
                ContinuousVoiceRun(
                    StreamingAsrFactory {
                        object : StreamingAsr, InterruptibleAsr {
                            override fun accept(samples: FloatArray): String {
                                while (!exit.get()) Thread.sleep(2)
                                return "late"
                            }

                            override fun finish() = "late"

                            override fun interrupt() {
                                exit.set(true)
                            }

                            override fun close() {
                                check(exit.get())
                                closed.set(true)
                            }
                        }
                    },
                    { recorder(sourceClosed) },
                    {
                        object : SpeechSegments {
                            override fun accept(samples: FloatArray) = listOf(FloatArray(512))

                            override fun finish() = emptyList<FloatArray>()

                            override fun close() = Unit
                        }
                    },
                    Owner(mutableListOf()),
                )
            try {
                withTimeout(5000) { run.run(AtomicBoolean(), AtomicBoolean()) }
                error("Saturation must propagate")
            } catch (failure: ContinuousCaptureFailure) {
                assertEquals(VoiceCapture.Failure.BACKPRESSURE, failure.failure)
            }
            assertTrue(closed.get() && sourceClosed.get())
        }

    private fun recorder(closed: AtomicBoolean) =
        object : PcmCapture {
            override fun read(samples: ShortArray): Int {
                Thread.sleep(5)
                return samples.size
            }

            override fun stop() = Unit

            override fun close() {
                closed.set(true)
            }
        }

    private class Owner(
        private val snapshots: MutableList<VoiceCapture.State>,
    ) : ContinuousCaptureOwner {
        override fun source(value: PcmCapture?) = Unit

        override fun asr(value: StreamingAsr?) = Unit

        override fun state(value: VoiceCapture.State) {
            snapshots += value
        }

        override fun phase(value: VoiceCapture.Phase) = Unit

        override fun interrupted() = error("No microphone")
    }
}
