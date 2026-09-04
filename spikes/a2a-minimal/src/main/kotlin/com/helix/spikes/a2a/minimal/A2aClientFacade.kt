package com.helix.spikes.a2a.minimal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

enum class A2aHttpBinding {
    JSON_RPC,
    HTTP_JSON,
}

data class A2aHttpRequest(
    val endpoint: String,
    val binding: A2aHttpBinding,
    val payload: String,
    val headers: Map<String, String> = emptyMap(),
)

data class A2aHttpResponse(
    val statusCode: Int,
    val body: String,
)

data class A2aSseEvent(
    val id: String?,
    val type: String?,
    val data: String,
)

interface A2aStreamHandle {
    fun cancel()
}

interface A2aStreamListener {
    fun onEvent(event: A2aSseEvent)

    fun onClosed()

    fun onFailure(failure: IOException)
}

interface A2aClientFacade {
    fun execute(request: A2aHttpRequest): A2aHttpResponse

    fun stream(
        request: A2aHttpRequest,
        listener: A2aStreamListener,
    ): A2aStreamHandle
}

class OkHttpA2aClientFacade(
    private val client: OkHttpClient,
    private val allowLoopbackHttp: Boolean = false,
    private val maxRequestBytes: Long = DEFAULT_MAX_BYTES,
    private val maxResponseBytes: Long = DEFAULT_MAX_BYTES,
) : A2aClientFacade {
    override fun execute(request: A2aHttpRequest): A2aHttpResponse {
        val wireRequest = wireRequest(request)
        return client.newCall(wireRequest).execute().use { response ->
            A2aHttpResponse(response.code, readBounded(response))
        }
    }

    override fun stream(
        request: A2aHttpRequest,
        listener: A2aStreamListener,
    ): A2aStreamHandle {
        val cancelled = AtomicBoolean(false)
        val eventSource =
            EventSources.createFactory(client).newEventSource(
                wireRequest(request),
                object : EventSourceListener() {
                    override fun onEvent(
                        eventSource: EventSource,
                        id: String?,
                        type: String?,
                        data: String,
                    ) {
                        if (cancelled.get()) return
                        require(data.toByteArray().size <= maxResponseBytes) { "A2A SSE event exceeds limit" }
                        require(Json.parseToJsonElement(data) is JsonObject) { "A2A SSE event must be a JSON object" }
                        listener.onEvent(A2aSseEvent(id, type, data))
                    }

                    override fun onClosed(eventSource: EventSource) {
                        if (!cancelled.get()) listener.onClosed()
                    }

                    override fun onFailure(
                        eventSource: EventSource,
                        t: Throwable?,
                        response: Response?,
                    ) {
                        if (!cancelled.get()) {
                            listener.onFailure(t.asIoException(response))
                        }
                    }
                },
            )
        return object : A2aStreamHandle {
            override fun cancel() {
                if (cancelled.compareAndSet(false, true)) eventSource.cancel()
            }
        }
    }

    private fun wireRequest(request: A2aHttpRequest): Request {
        val endpoint = request.endpoint.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid A2A endpoint")
        validateEndpoint(endpoint)
        val bytes = request.payload.toByteArray()
        require(bytes.size <= maxRequestBytes) { "A2A request exceeds limit" }
        require(Json.parseToJsonElement(request.payload) is JsonObject) { "A2A request must be a JSON object" }
        val mediaType =
            when (request.binding) {
                A2aHttpBinding.JSON_RPC -> JSON_MEDIA_TYPE
                A2aHttpBinding.HTTP_JSON -> A2A_MEDIA_TYPE
            }
        val builder = Request.Builder().url(endpoint).post(bytes.toRequestBody(mediaType))
        request.headers.forEach { (name, value) ->
            require('\r' !in name && '\n' !in name && '\r' !in value && '\n' !in value) {
                "A2A header contains a line break"
            }
            builder.header(name, value)
        }
        return builder.build()
    }

    private fun validateEndpoint(endpoint: HttpUrl) {
        if (endpoint.isHttps) return
        require(allowLoopbackHttp && endpoint.scheme == "http" && endpoint.host.isLiteralLoopback()) {
            "A2A endpoint must use HTTPS"
        }
    }

    private fun readBounded(response: Response): String {
        val body = response.body
        body.contentLength().takeIf { it >= 0 }?.let {
            require(it <= maxResponseBytes) { "A2A response exceeds limit" }
        }
        return body.byteStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size().toLong() + count <= maxResponseBytes) { "A2A response exceeds limit" }
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        }
    }

    private fun String.isLiteralLoopback(): Boolean = this == "127.0.0.1" || this == "::1"

    private fun Throwable?.asIoException(response: Response?): IOException =
        when (this) {
            is IOException -> this
            null -> IOException("A2A SSE failed with HTTP ${response?.code ?: "unknown"}")
            else -> IOException("A2A SSE failed", this)
        }

    companion object {
        private const val DEFAULT_MAX_BYTES = 2L * 1024 * 1024
        private const val BUFFER_SIZE = 8192
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val A2A_MEDIA_TYPE = "application/a2a+json; charset=utf-8".toMediaType()
    }
}
