package com.helix.app.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.R
import com.helix.app.ui.speechFailureText
import com.helix.app.ui.voiceStatusText
import com.helix.provider.api.wire.SocketHandshakeFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Synthetic speech transports and silent audio fixtures only; no accounts, microphone or network. */
class CloudSpeechFailureDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private data class Case(
        val error: Exception,
        val category: SpeechFailure,
        val message: Int,
    )

    private fun cases() =
        listOf(
            Case(SocketHandshakeFailure(503), SpeechFailure.SERVICE, R.string.speech_failure_service),
            Case(SocketTimeoutException("private-token"), SpeechFailure.TIMEOUT, R.string.speech_failure_timeout),
            Case(SocketHandshakeFailure(401), SpeechFailure.AUTHENTICATION, R.string.speech_failure_authentication),
            Case(SpeechProtocolFailure("private-token"), SpeechFailure.PROTOCOL, R.string.speech_failure_protocol),
        )

    private fun await(condition: () -> Boolean) =
        runBlocking {
            withTimeout(5000) { while (!condition()) delay(10) }
        }

    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    @Test fun captureFailureKeepsPartialEditableButNeverFinalAndRetryClearsError() {
        for (case in cases()) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val failure = AtomicReference<Exception?>(case.error)
            val closed = AtomicInteger()
            lateinit var capture: VoiceCapture
            val factory =
                CloudAsrFactory("https://speech.invalid") {
                    object : CloudAsrConnection {
                        override fun send(samples: FloatArray): String {
                            capture.finish()
                            return "partial only"
                        }

                        override fun finish(): String {
                            failure.get()?.let { throw it }
                            return "verified final"
                        }

                        override fun close() {
                            closed.incrementAndGet()
                        }
                    }
                }
            capture =
                VoiceCapture(context, scope, factory) {
                    object : PcmCapture {
                        override fun read(samples: ShortArray) = samples.size

                        override fun stop() = Unit

                        override fun close() = Unit
                    }
                }
            try {
                capture.start()
                await { capture.state.value.phase == VoiceCapture.Phase.FAILED && closed.get() == 1 }
                val state = capture.state.value
                assertEquals("partial only", state.text)
                assertEquals(case.category, state.serviceFailure)
                assertEquals(case.message, voiceStatusText(state, true))
                assertFalse(context.getString(voiceStatusText(state, true)).contains("private-token"))
                failure.set(null)
                runBlocking { capture.retry() }
                await { capture.state.value.phase == VoiceCapture.Phase.REVIEW && closed.get() == 2 }
                assertEquals("verified final", capture.state.value.text)
                assertNull(capture.state.value.serviceFailure)
                assertNull(capture.state.value.failure)
            } finally {
                capture.cancel()
                runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
            }
        }
    }

    @Test fun playbackFailureReachesUiWithoutAudioOrOfflineFallbackAndRecoveryClearsError() {
        for (case in cases()) {
            val root = File(context.cacheDir, "speech-failure-${UUID.randomUUID()}")
            val failure = AtomicReference<Exception?>(case.error)
            val writes = AtomicInteger()
            val closes = AtomicInteger()
            val service =
                ReadAloudService(
                    LocalSpeechModel(context, root),
                    { error("Offline fallback must not run") },
                    { AutoCloseable {} },
                    {
                        object : SpeechSink {
                            override fun write(
                                samples: FloatArray,
                                keepGoing: () -> Boolean,
                            ) {
                                writes.incrementAndGet()
                            }

                            override fun finish(keepGoing: () -> Boolean) = Unit

                            override fun interrupt() = Unit

                            override fun close() = Unit
                        }
                    },
                    cloudEngine = {
                        CloudSpeechEngine(
                            object : CloudTtsConnection {
                                override fun stream(
                                    text: String,
                                    consume: (FloatArray, Int) -> Unit,
                                ) {
                                    failure.get()?.let { throw it }
                                    consume(floatArrayOf(0f), 24000)
                                }

                                override fun close() {
                                    closes.incrementAndGet()
                                }
                            },
                        )
                    },
                )
            try {
                main { service.play("failure", "Synthetic speech") }
                await { service.state.value.phase == ReadAloudPhase.FAILED && closes.get() == 1 }
                assertEquals(0, writes.get())
                assertEquals(case.category, service.state.value.serviceFailure)
                assertEquals(case.message, speechFailureText(requireNotNull(service.state.value.serviceFailure)))
                assertFalse(context.getString(case.message).contains("private-token"))
                failure.set(null)
                main { service.play("recovery", "Synthetic speech") }
                await { service.state.value.phase == ReadAloudPhase.IDLE && closes.get() == 2 }
                assertEquals(1, writes.get())
                assertNull(service.state.value.serviceFailure)
            } finally {
                main(service::close)
                root.deleteRecursively()
            }
        }
    }
}
