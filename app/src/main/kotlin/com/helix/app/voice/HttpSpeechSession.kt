package com.helix.app.voice

import com.helix.core.model.NormalizedEndpoint
import com.helix.provider.api.wire.HttpFailureDetails
import com.helix.provider.api.wire.OkHttpWireClient
import com.helix.provider.api.wire.WireBody
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import com.helix.provider.api.wire.httpFailureDetails
import com.helix.provider.api.wire.readHttpFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Single active HTTP exchange; neither completed nor partially consumed requests are replayed. */
class HttpSpeechSession(
    private val config: CloudSpeechConfig,
    private val wire: WireClient =
        OkHttpWireClient(
            connectTimeoutMillis = config.timeoutMillis.toLong(),
            readTimeoutMillis = config.timeoutMillis.toLong(),
            maxBodyBytes = 48_000_000,
            followRedirects = false,
        ),
    private val retryScope: String = "anonymous",
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val active = AtomicReference<Job?>(null)
    private val activeBody = AtomicReference<WireBody?>(null)

    init {
        require(config.protocol == PROTOCOL)
        NormalizedEndpoint.parse(config.endpoint.toString())
    }

    fun post(
        contentType: String,
        body: ByteArray,
    ): ByteArray =
        exchange(contentType, body) { response ->
            response.body.bytes().also { ensureOpen() }
        }

    fun postStream(
        contentType: String,
        body: ByteArray,
        consume: (ByteArray) -> Unit,
    ) {
        exchange(contentType, body) { response ->
            validatePcmHeaders(response.headers)
            var bytes = 0L
            response.body.forEachChunk { chunk ->
                currentCoroutineContext().ensureActive()
                ensureOpen()
                bytes += chunk.size
                require(bytes <= MAX_STREAM_BYTES) { "SPEECH_RESPONSE_LIMIT" }
                consume(chunk)
                ensureOpen()
                true
            }
        }
    }

    private fun <T> exchange(
        contentType: String,
        body: ByteArray,
        consume: suspend (WireResponse) -> T,
    ): T =
        runBlocking {
            var responseRequestId: String? = null
            try {
                withTimeout(config.timeoutMillis.toLong()) {
                    ensureOpen()
                    val job = currentCoroutineContext().job
                    check(active.compareAndSet(null, job))
                    try {
                        ensureOpen()
                        val response = open(contentType, body)
                        responseRequestId = httpFailureDetails(response.status, response.headers).requestId
                        activeBody.set(response.body)
                        try {
                            ensureOpen()
                            checkStatus(response)
                            consume(response)
                        } finally {
                            activeBody.compareAndSet(response.body, null)
                            response.body.close()
                        }
                    } finally {
                        active.compareAndSet(job, null)
                    }
                }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                throw transportFailure(java.net.SocketTimeoutException("Speech request timeout"), responseRequestId)
            } catch (error: java.io.IOException) {
                ensureOpen()
                throw transportFailure(error, responseRequestId)
            }
        }

    private fun transportFailure(
        error: java.io.IOException,
        requestId: String?,
    ): java.io.IOException = requestId?.let { SpeechTransportFailure(error, it) } ?: error

    private suspend fun open(
        contentType: String,
        body: ByteArray,
    ): WireResponse {
        SpeechRetryCooldown.remaining("$retryScope|${config.endpoint}")?.let { remaining ->
            throw SpeechHttpFailure(429, HttpFailureDetails(429, retryAfterMillis = remaining))
        }
        return wire.open(WireRequest("POST", config.endpoint.toString(), mapOf("Content-Type" to contentType), body))
    }

    private suspend fun checkStatus(response: WireResponse) {
        if (response.status == 200) return
        val details = readHttpFailure(response)
        if (response.status == 429) {
            details.retryAfterMillis?.let { SpeechRetryCooldown.record("$retryScope|${config.endpoint}", it) }
        }
        throw SpeechHttpFailure(response.status, details)
    }

    private fun validatePcmHeaders(headers: Map<String, List<String>>) {
        val rate =
            headers.entries
                .firstOrNull { it.key.equals("X-Audio-Sample-Rate", true) }
                ?.value
                ?.firstOrNull()
        require(rate == null || rate.trim().toIntOrNull() == 24000) { "PCM_SAMPLE_RATE_INVALID" }
        val type =
            headers.entries
                .firstOrNull { it.key.equals("Content-Type", true) }
                ?.value
                ?.firstOrNull()
        val mediaType = type?.substringBefore(';')?.trim()
        require(mediaType == null || mediaType.equals("audio/pcm", true)) { "PCM_CONTENT_TYPE_INVALID" }
    }

    private fun ensureOpen() {
        if (closed.get()) throw CancellationException("Speech request cancelled")
    }

    override fun close() {
        closed.set(true)
        active.getAndSet(null)?.cancel()
        activeBody.getAndSet(null)?.close()
    }

    companion object {
        const val PROTOCOL = "http-file-wav-v1"
        internal const val MAX_STREAM_BYTES = 48_000_000L

        fun config(endpoint: String) =
            CloudSpeechConfig(
                URI(NormalizedEndpoint.parse(endpoint).full),
                PROTOCOL,
                180_000,
                allowCleartext = true,
            )
    }
}
