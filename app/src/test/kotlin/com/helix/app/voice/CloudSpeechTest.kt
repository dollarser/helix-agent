package com.helix.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** In-memory test protocol only. These fakes do not describe a deployed server wire protocol. */
class CloudSpeechTest {
    @Test fun cancelledFinalCannotBecomeTheNextCaptureResult() {
        val waiting = CountDownLatch(1)
        val released = CountDownLatch(1)
        val result = AtomicReference<String?>()
        val failure = AtomicReference<Throwable?>()
        val old =
            CloudAsrFactory {
                object : CloudAsrConnection {
                    override fun send(samples: FloatArray) = "old partial"

                    override fun finish(): String {
                        waiting.countDown()
                        check(released.await(2, TimeUnit.SECONDS))
                        return "old final"
                    }

                    override fun close() {
                        released.countDown()
                    }
                }
            }.open()
        val worker =
            thread {
                try {
                    result.set(old.finish())
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }
        try {
            assertTrue(waiting.await(2, TimeUnit.SECONDS))
            (old as InterruptibleAsr).interrupt()
            val next =
                CloudAsrFactory {
                    object : CloudAsrConnection {
                        override fun send(samples: FloatArray) = "new partial"

                        override fun finish() = "new final"

                        override fun close() = Unit
                    }
                }.open()
            next.use {
                assertEquals("new partial", it.accept(floatArrayOf(0f)))
                assertEquals("new final", it.finish())
            }
            worker.join(2000)
            assertTrue(!worker.isAlive)
            assertEquals(null, result.get())
            assertTrue(failure.get() is IllegalStateException)
        } finally {
            old.close()
            worker.join(2000)
        }
    }

    @Test fun oldSessionAudioCannotEnterNewSessionEngine() {
        var late: ((FloatArray, Int) -> Unit)? = null
        val played = mutableListOf<Float>()
        val old =
            CloudSpeechEngine(
                object : CloudTtsConnection {
                    override fun stream(
                        text: String,
                        consume: (FloatArray, Int) -> Unit,
                    ) {
                        late = consume
                        consume(floatArrayOf(0.1f), 16000)
                    }

                    override fun close() = Unit
                },
            )
        old.generate("old reply", { true }) { samples, _ -> played.add(samples.single()) }
        old.interrupt()
        val next =
            CloudSpeechEngine(
                object : CloudTtsConnection {
                    override fun stream(
                        text: String,
                        consume: (FloatArray, Int) -> Unit,
                    ) {
                        requireNotNull(late).invoke(floatArrayOf(0.9f), 16000)
                        consume(floatArrayOf(0.2f), 16000)
                    }

                    override fun close() = Unit
                },
            )
        next.use { it.generate("new reply", { true }) { samples, _ -> played.add(samples.single()) } }
        assertEquals(listOf(0.1f, 0.2f), played)
    }

    @Test fun completedTtsRequestDropsCallbacksWhileTheNextRequestRuns() {
        var late: ((FloatArray, Int) -> Unit)? = null
        val played = mutableListOf<Float>()
        val engine =
            CloudSpeechEngine(
                object : CloudTtsConnection {
                    override fun stream(
                        text: String,
                        consume: (FloatArray, Int) -> Unit,
                    ) {
                        late?.invoke(floatArrayOf(0.9f), 16000)
                        late = consume
                        consume(floatArrayOf(0.2f), 16000)
                    }

                    override fun close() = Unit
                },
            )
        engine.use {
            repeat(2) { _ -> it.generate("reply chunk", { true }) { samples, _ -> played.add(samples.single()) } }
        }
        assertEquals(listOf(0.2f, 0.2f), played)
    }

    @Test fun interruptionUnblocksWaitingTtsWithoutAnotherPacket() {
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val engine =
            CloudSpeechEngine(
                object : CloudTtsConnection {
                    override fun stream(
                        text: String,
                        consume: (FloatArray, Int) -> Unit,
                    ) {
                        entered.countDown()
                        check(closed.await(2, TimeUnit.SECONDS))
                        consume(floatArrayOf(0.5f), 16000)
                    }

                    override fun close() {
                        closed.countDown()
                    }
                },
            )
        val worker =
            thread {
                try {
                    engine.generate("回答", { true }) { _, _ -> error("late audio accepted") }
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            engine.interrupt()
            worker.join(2000)
            assertTrue(!worker.isAlive)
            assertEquals(null, failure.get())
        } finally {
            engine.close()
            worker.join(2000)
        }
    }

    @Test fun asrPartialAndFinalRemainSeparateAndCloseIsIdempotent() {
        var closes = 0
        val asr =
            CloudAsrFactory {
                object : CloudAsrConnection {
                    override fun send(samples: FloatArray) = "部分"

                    override fun finish() = "最终文字"

                    override fun close() {
                        closes++
                    }
                }
            }.open()
        assertEquals("部分", asr.accept(floatArrayOf(0f)))
        assertEquals("最终文字", asr.finish())
        assertThrows(IllegalStateException::class.java) { asr.accept(floatArrayOf(0f)) }
        asr.close()
        asr.close()
        assertEquals(1, closes)
    }

    @Test fun asrFailureDoesNotPromotePartialToFinal() {
        val asr =
            CloudAsrFactory {
                object : CloudAsrConnection {
                    override fun send(samples: FloatArray) = "partial"

                    override fun finish(): String = error("connection lost")

                    override fun close() = Unit
                }
            }.open()
        asr.use {
            assertEquals("partial", it.accept(floatArrayOf(0f)))
            assertThrows(IllegalStateException::class.java) { it.finish() }
        }
    }

    @Test fun cancelledAsrRejectsLateResponse() {
        lateinit var asr: StreamingAsr
        asr =
            CloudAsrFactory {
                object : CloudAsrConnection {
                    override fun send(samples: FloatArray): String {
                        (asr as InterruptibleAsr).interrupt()
                        return "late"
                    }

                    override fun finish() = ""

                    override fun close() = Unit
                }
            }.open()
        assertThrows(IllegalStateException::class.java) { asr.accept(floatArrayOf(0f)) }
    }

    @Test fun ttsStopClosesConnectionAndDropsLateAudio() {
        var closes = 0
        var parts = 0
        lateinit var engine: CloudSpeechEngine
        engine =
            CloudSpeechEngine(
                object : CloudTtsConnection {
                    override fun stream(
                        text: String,
                        consume: (FloatArray, Int) -> Unit,
                    ) {
                        consume(floatArrayOf(0.5f), 16000)
                        engine.interrupt()
                        consume(floatArrayOf(0.7f), 16000)
                    }

                    override fun close() {
                        closes++
                    }
                },
            )
        engine.generate("用户可见回答", { true }) { _, _ -> parts++ }
        engine.close()
        assertEquals(1, parts)
        assertEquals(1, closes)
    }

    @Test fun ttsCopiesBorrowedAudioAndRejectsRateChanges() {
        var received = floatArrayOf()
        val engine =
            CloudSpeechEngine(
                object : CloudTtsConnection {
                    override fun stream(
                        text: String,
                        consume: (FloatArray, Int) -> Unit,
                    ) {
                        val buffer = floatArrayOf(0.5f)
                        consume(buffer, 16000)
                        buffer[0] = 0f
                        consume(buffer, 24000)
                    }

                    override fun close() = Unit
                },
            )
        engine.use {
            assertThrows(IllegalArgumentException::class.java) {
                it.generate("回答", { true }) { samples, _ -> received = samples }
            }
        }
        assertEquals(0.5f, received[0])
    }

    @Test fun emptyOrMalformedAudioFails() {
        fun engine(samples: FloatArray?) =
            CloudSpeechEngine(
                object : CloudTtsConnection {
                    override fun stream(
                        text: String,
                        consume: (FloatArray, Int) -> Unit,
                    ) {
                        samples?.let { consume(it, 16000) }
                    }

                    override fun close() = Unit
                },
            )
        engine(null).use {
            assertThrows(IllegalStateException::class.java) { it.generate("回答", { true }) { _, _ -> } }
        }
        engine(floatArrayOf(Float.NaN)).use {
            assertThrows(IllegalArgumentException::class.java) { it.generate("回答", { true }) { _, _ -> } }
        }
    }

    @Test fun configurationRequiresExplicitSecureServiceAndNoEmbeddedSecrets() {
        val config = CloudSpeechConfig(URI("wss://speech.example.test/asr"), "test-only-v1")
        assertTrue(config.timeoutMillis > 0)
        listOf(
            "http://speech.example.test",
            "https://user:secret@speech.example.test",
            "https://speech.example.test?key=secret",
        ).forEach {
            assertThrows(IllegalArgumentException::class.java) { CloudSpeechConfig(URI(it), "test-only-v1") }
        }
    }
}
