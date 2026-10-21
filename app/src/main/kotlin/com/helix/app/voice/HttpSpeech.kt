package com.helix.app.voice

import com.helix.core.model.NormalizedEndpoint
import com.helix.provider.api.wire.OkHttpWireClient
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Confirmed HTTP file/WAV contract. Not a WebSocket or partial-ASR implementation. */
class HttpSpeechSession(
    private val config: CloudSpeechConfig,
    private val wire: WireClient =
        OkHttpWireClient(
            connectTimeoutMillis = config.timeoutMillis.toLong(),
            readTimeoutMillis = config.timeoutMillis.toLong(),
            maxBodyBytes = 12_000_000,
            followRedirects = false,
            retryConnections = false,
        ),
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val active = AtomicReference<Job?>(null)

    init {
        require(config.protocol == PROTOCOL)
        NormalizedEndpoint.parse(config.endpoint.toString())
    }

    fun post(
        contentType: String,
        body: ByteArray,
    ): ByteArray =
        runBlocking {
            try {
                withTimeout(config.timeoutMillis.toLong()) {
                    check(!closed.get())
                    val job = currentCoroutineContext().job
                    check(active.compareAndSet(null, job))
                    try {
                        check(!closed.get())
                        val response =
                            wire.open(
                                WireRequest(
                                    "POST",
                                    config.endpoint.toString(),
                                    mapOf("Content-Type" to contentType),
                                    body,
                                ),
                            )
                        try {
                            check(response.status == 200) { "SPEECH_HTTP_${response.status}" }
                            response.body.bytes().also { check(!closed.get()) }
                        } finally {
                            response.body.close()
                        }
                    } finally {
                        active.compareAndSet(job, null)
                    }
                }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                throw java.net.SocketTimeoutException("Speech request timeout")
            }
        }

    override fun close() {
        closed.set(true)
        active.getAndSet(null)?.cancel()
    }

    companion object {
        const val PROTOCOL = "http-file-wav-v1"

        fun config(endpoint: String) =
            CloudSpeechConfig(
                URI(NormalizedEndpoint.parse(endpoint).full),
                PROTOCOL,
                180_000,
                allowCleartext = true,
            )
    }
}

/** Keep only this user-started capture in memory; upload once at explicit end-of-input. */
class HttpFileAsr(
    private val session: HttpSpeechSession,
) : CloudAsrConnection {
    private val pcm = ByteArrayOutputStream()
    private val closed = AtomicBoolean()

    override fun send(samples: FloatArray): String {
        check(!closed.get())
        check(pcm.size() + samples.size * 2 <= 16000 * 120 * 2) { "ASR_DURATION_LIMIT" }
        pcm.write(encodeSpeechPcm(samples))
        return ""
    }

    override fun finish(): String {
        check(!closed.get())
        require(pcm.size() > 0)
        val wav = SpeechWav.encode(pcm.toByteArray(), 16000)
        val boundary = "helix-speech-file"
        val start =
            "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"capture.wav\"\r\n" +
                "Content-Type: audio/wav\r\n\r\n"
        val body = start.toByteArray() + wav + "\r\n--$boundary--\r\n".toByteArray()
        val response = session.post("multipart/form-data; boundary=$boundary", body)
        check(response.size <= 100_000) { "ASR_RESPONSE_LIMIT" }
        val text = Json.parseToJsonElement(response.toString(Charsets.UTF_8)).jsonObject["text"] as? JsonPrimitive
        require(text != null && text.isString && text.content.length <= 20_000) { "ASR_RESPONSE_INVALID" }
        return text.content
    }

    override fun close() {
        closed.set(true)
        session.close()
    }
}

/** One complete HTTP synthesis per submitted text piece; WAV is decoded into bounded PCM callbacks. */
class HttpWavTts(
    private val session: HttpSpeechSession,
) : CloudTtsConnection {
    override fun stream(
        text: String,
        consume: (FloatArray, Int) -> Unit,
    ) {
        require(text.length in 1..2000)
        val body = buildJsonObject { put("input", text) }.toString().toByteArray()
        SpeechWav.consume(session.post("application/json", body), consume)
    }

    override fun close() = session.close()
}
