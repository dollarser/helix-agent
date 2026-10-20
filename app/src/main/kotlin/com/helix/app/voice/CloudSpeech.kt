package com.helix.app.voice

import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/** Configuration only; no protocol, model, credentials or network access is inferred from the LLM. */
data class CloudSpeechConfig(
    val endpoint: URI,
    val protocol: String,
    val timeoutMillis: Int = 15_000,
    val allowCleartext: Boolean = false,
) {
    init {
        val schemes = if (allowCleartext) setOf("https", "wss", "http") else setOf("https", "wss")
        require(endpoint.scheme in schemes && !endpoint.host.isNullOrBlank())
        require(endpoint.userInfo == null && endpoint.fragment == null && endpoint.query == null)
        require(protocol.isNotBlank() && protocol.length <= 80)
        require(timeoutMillis in 1_000..60_000)
    }
}

/** Transport implementations must bound connect/send/final waits by config.timeoutMillis.
 * close must be thread-safe, idempotent and unblock pending I/O. No automatic retry/upload replay.
 * Authentication and wire encoding belong to the service-specific adapter, never to these ports.
 */
interface CloudAsrConnection : AutoCloseable {
    /** Ordered mono 16 kHz normalized PCM. Return the latest complete transcript snapshot, not a delta. */
    fun send(samples: FloatArray): String

    /** Signal end-of-input and wait for a final transcript or throw; partial is never a final fallback. */
    fun finish(): String
}

/** Synchronous callbacks provide backpressure. No callbacks may survive stream returning/throwing.
 * close must unblock stream even if the server never sends another audio packet.
 */
interface CloudTtsConnection : AutoCloseable {
    fun stream(
        text: String,
        consume: (FloatArray, Int) -> Unit,
    )
}

class CloudAsrFactory(
    override val destination: String? = null,
    override val supportsPartial: Boolean = true,
    private val connect: () -> CloudAsrConnection,
) : StreamingAsrFactory {
    override val uploadsAudio = true

    override fun open(): StreamingAsr {
        val connection = connect()
        val closed = AtomicBoolean()
        var finished = false
        var samplesSent = 0
        return object : InterruptibleAsr {
            override fun accept(samples: FloatArray): String {
                check(!closed.get() && !finished)
                require(samples.size in 1..16000 && samples.all { it.isFinite() && it in -1f..1f })
                samplesSent += samples.size
                check(samplesSent <= 16000 * 120) { "ASR_DURATION_LIMIT" }
                return checkedText(connection.send(samples)).also { check(!closed.get()) }
            }

            override fun finish(): String {
                check(!closed.get() && !finished)
                finished = true
                return checkedText(connection.finish()).also { check(!closed.get()) }
            }

            override fun close() {
                if (closed.compareAndSet(false, true)) connection.close()
            }

            override fun interrupt() = close()
        }
    }

    private fun checkedText(text: String): String {
        require(text.length <= 20_000) { "ASR_TEXT_LIMIT" }
        return text
    }
}

/** Optional interrupt hook: only network engines support concurrent interruption; native JNI does not. */
interface InterruptibleSpeechEngine : SpeechEngine {
    fun interrupt()
}

class CloudSpeechEngine(
    private val connection: CloudTtsConnection,
) : InterruptibleSpeechEngine {
    private val closed = AtomicBoolean()

    override fun generate(
        text: String,
        keepGoing: () -> Boolean,
        consume: (FloatArray, Int) -> Unit,
    ) {
        check(!closed.get())
        require(text.isNotBlank() && text.length <= 20_000)
        var rate: Int? = null
        var frames = 0L
        val receiving = AtomicBoolean(true)
        try {
            connection.stream(text) { samples, sampleRate ->
                if (receiving.get() && !closed.get() && keepGoing()) {
                    require(sampleRate in 8000..48000 && (rate == null || rate == sampleRate))
                    require(samples.size in 1..sampleRate && samples.all { it.isFinite() && it in -1f..1f })
                    rate = sampleRate
                    frames += samples.size
                    check(frames <= sampleRate * 120L) { "TTS_DURATION_LIMIT" }
                    consume(samples.copyOf(), sampleRate)
                }
            }
        } finally {
            receiving.set(false)
        }
        check(closed.get() || !keepGoing() || frames > 0) { "NO_SYNTHESIZED_AUDIO" }
    }

    override fun interrupt() = close()

    override fun close() {
        if (closed.compareAndSet(false, true)) connection.close()
    }
}
