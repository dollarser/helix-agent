package com.helix.extensions.a2a

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
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
import java.util.concurrent.atomic.AtomicLong

@Suppress("TooManyFunctions") // one transport keeps request construction, bounds, and binding variants consistent
internal class OkHttpA2aTaskClient(
    private val client: OkHttpClient,
) : A2aTaskClient {
    override fun send(
        interfaceSnapshot: A2aInterfaceSnapshot,
        bearer: String?,
        submission: A2aTaskSubmission,
    ): A2aTaskUpdate =
        executeOperation(
            interfaceSnapshot,
            bearer,
            Operation.SEND,
            A2aTaskPayloads.sendParams(submission, interfaceSnapshot.tenant),
            taskId = null,
            requestId = submission.messageId,
        )

    override fun sendStreaming(
        interfaceSnapshot: A2aInterfaceSnapshot,
        bearer: String?,
        submission: A2aTaskSubmission,
        listener: A2aTaskStreamListener,
    ): A2aTaskStreamHandle =
        streamOperation(
            interfaceSnapshot,
            bearer,
            Operation.SEND_STREAM,
            A2aTaskPayloads.sendParams(submission, interfaceSnapshot.tenant),
            taskId = null,
            requestId = submission.messageId,
            lastEventId = null,
            listener = listener,
        )

    override fun getTask(
        interfaceSnapshot: A2aInterfaceSnapshot,
        bearer: String?,
        taskId: String,
    ): A2aTaskUpdate =
        executeOperation(
            interfaceSnapshot,
            bearer,
            Operation.GET,
            A2aTaskPayloads.taskParams(taskId, interfaceSnapshot.tenant),
            taskId,
            requestId = "get-${taskId.take(128)}",
        )

    override fun cancelTask(
        interfaceSnapshot: A2aInterfaceSnapshot,
        bearer: String?,
        taskId: String,
    ): A2aTaskUpdate =
        executeOperation(
            interfaceSnapshot,
            bearer,
            Operation.CANCEL,
            A2aTaskPayloads.taskParams(taskId, interfaceSnapshot.tenant),
            taskId,
            requestId = "cancel-${taskId.take(128)}",
        )

    override fun subscribeToTask(
        interfaceSnapshot: A2aInterfaceSnapshot,
        bearer: String?,
        taskId: String,
        lastEventId: String?,
        listener: A2aTaskStreamListener,
    ): A2aTaskStreamHandle =
        streamOperation(
            interfaceSnapshot,
            bearer,
            Operation.SUBSCRIBE,
            A2aTaskPayloads.taskParams(taskId, interfaceSnapshot.tenant),
            taskId,
            requestId = "subscribe-${taskId.take(128)}",
            lastEventId,
            listener,
        )

    @Suppress("ThrowsCount") // preserves the distinction between HTTP, validation, and I/O failures
    private fun executeOperation(
        interfaceSnapshot: A2aInterfaceSnapshot,
        bearer: String?,
        operation: Operation,
        params: JsonObject,
        taskId: String?,
        requestId: String,
    ): A2aTaskUpdate {
        val request = wireRequest(interfaceSnapshot, bearer, operation, params, taskId, requestId, null)
        try {
            return client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw A2aTransportFailure(
                        "A2A ${operation.method} failed with HTTP ${response.code}",
                        requestMayHaveArrived = operation.mutating && response.code >= 500,
                    )
                }
                require(response.isJson()) { "A2A response is not JSON" }
                val root = parseObject(readBounded(response))
                val result =
                    if (interfaceSnapshot.binding == A2aBinding.JSON_RPC) {
                        A2aTaskPayloads.unwrapJsonRpc(root)
                    } else {
                        root
                    }
                A2aTaskPayloads.parseUpdate(result, sequence = 0)
            }
        } catch (failure: A2aTransportFailure) {
            throw failure
        } catch (failure: IOException) {
            throw A2aTransportFailure(
                "A2A ${operation.method} transport failed",
                requestMayHaveArrived = operation.mutating,
                cause = failure,
            )
        }
    }

    @Suppress("LongParameterList", "TooGenericExceptionCaught") // malformed SSE data is isolated at the event boundary
    private fun streamOperation(
        interfaceSnapshot: A2aInterfaceSnapshot,
        bearer: String?,
        operation: Operation,
        params: JsonObject,
        taskId: String?,
        requestId: String,
        lastEventId: String?,
        listener: A2aTaskStreamListener,
    ): A2aTaskStreamHandle {
        val cancelled = AtomicBoolean(false)
        val sequence = AtomicLong(-1)
        val request = wireRequest(interfaceSnapshot, bearer, operation, params, taskId, requestId, lastEventId)
        val source =
            EventSources.createFactory(client).newEventSource(
                request,
                object : EventSourceListener() {
                    override fun onOpen(
                        eventSource: EventSource,
                        response: Response,
                    ) {
                        if (!response.isSuccessful || !response.isSse()) {
                            eventSource.cancel()
                            if (!cancelled.get()) {
                                listener.onFailure(
                                    A2aTransportFailure(
                                        "A2A ${operation.method} did not open an SSE stream",
                                        requestMayHaveArrived = operation.mutating,
                                    ),
                                )
                            }
                        }
                    }

                    override fun onEvent(
                        eventSource: EventSource,
                        id: String?,
                        type: String?,
                        data: String,
                    ) {
                        if (cancelled.get()) return
                        try {
                            require(data.toByteArray().size <= MAX_SSE_EVENT_BYTES) { "A2A SSE event exceeds limit" }
                            val root = parseObject(data)
                            val result =
                                if (interfaceSnapshot.binding == A2aBinding.JSON_RPC) {
                                    A2aTaskPayloads.unwrapJsonRpc(root)
                                } else {
                                    root
                                }
                            listener.onUpdate(A2aTaskPayloads.parseUpdate(result, sequence.incrementAndGet(), id))
                        } catch (failure: Exception) {
                            eventSource.cancel()
                            listener.onFailure(A2aTransportFailure("A2A SSE event is invalid", true, failure))
                        }
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
                            listener.onFailure(
                                A2aTransportFailure(
                                    "A2A ${operation.method} stream failed",
                                    requestMayHaveArrived = operation.mutating,
                                    cause = t,
                                ),
                            )
                        }
                    }
                },
            )
        return A2aTaskStreamHandle {
            if (cancelled.compareAndSet(false, true)) source.cancel()
        }
    }

    @Suppress("LongParameterList")
    private fun wireRequest(
        interfaceSnapshot: A2aInterfaceSnapshot,
        bearer: String?,
        operation: Operation,
        params: JsonObject,
        taskId: String?,
        requestId: String,
        lastEventId: String?,
    ): Request {
        validateBearer(bearer)
        val builder =
            Request
                .Builder()
                .header("A2A-Version", interfaceSnapshot.protocolVersion)
                .apply {
                    bearer?.let { header("Authorization", "Bearer $it") }
                    lastEventId?.let {
                        require(it.isNotBlank() && it.length <= 512 && '\r' !in it && '\n' !in it) {
                            "A2A Last-Event-ID is invalid"
                        }
                        header("Last-Event-ID", it)
                    }
                }
        val request =
            when (interfaceSnapshot.binding) {
                A2aBinding.JSON_RPC -> {
                    val envelope =
                        buildJsonObject {
                            put("jsonrpc", "2.0")
                            put("id", requestId)
                            put("method", operation.method)
                            put("params", params)
                        }.toString()
                    builder
                        .url(interfaceSnapshot.endpoint.full)
                        .post(boundedBody(envelope, JSON_MEDIA_TYPE))
                }

                A2aBinding.HTTP_JSON -> {
                    restRequest(builder, interfaceSnapshot, operation, params, taskId)
                }
            }
        return request.build()
    }

    private fun restRequest(
        builder: Request.Builder,
        interfaceSnapshot: A2aInterfaceSnapshot,
        operation: Operation,
        params: JsonObject,
        taskId: String?,
    ): Request.Builder {
        val url = restUrl(interfaceSnapshot.endpoint.full.toHttpUrl(), interfaceSnapshot.tenant, operation, taskId)
        return when (operation) {
            Operation.GET -> builder.url(url).get()
            Operation.SUBSCRIBE -> builder.url(url).post(boundedBody(params.toString(), A2A_MEDIA_TYPE))
            else -> builder.url(url).post(boundedBody(params.toString(), A2A_MEDIA_TYPE))
        }
    }

    private fun restUrl(
        base: HttpUrl,
        tenant: String?,
        operation: Operation,
        taskId: String?,
    ): HttpUrl =
        base
            .newBuilder()
            .apply {
                tenant?.let { addPathSegment(it) }
                when (operation) {
                    Operation.SEND -> {
                        addPathSegment("message:send")
                    }

                    Operation.SEND_STREAM -> {
                        addPathSegment("message:stream")
                    }

                    Operation.GET -> {
                        addPathSegment("tasks")
                        addPathSegment(requireNotNull(taskId))
                    }

                    Operation.CANCEL -> {
                        addPathSegment("tasks")
                        addPathSegment("${requireNotNull(taskId)}:cancel")
                    }

                    Operation.SUBSCRIBE -> {
                        addPathSegment("tasks")
                        addPathSegment("${requireNotNull(taskId)}:subscribe")
                    }
                }
            }.build()

    private fun boundedBody(
        body: String,
        mediaType: okhttp3.MediaType,
    ): okhttp3.RequestBody {
        val bytes = body.toByteArray()
        require(bytes.size <= MAX_REQUEST_BYTES) { "A2A request exceeds limit" }
        return bytes.toRequestBody(mediaType)
    }

    private fun readBounded(response: Response): String {
        val body = response.body
        body.contentLength().takeIf { it >= 0 }?.let {
            require(it <= MAX_RESPONSE_BYTES) { "A2A response exceeds limit" }
        }
        return body.byteStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_RESPONSE_BYTES) { "A2A response exceeds limit" }
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        }
    }

    private fun parseObject(raw: String): JsonObject =
        Json.parseToJsonElement(raw) as? JsonObject ?: error("A2A response must be a JSON object")

    private fun validateBearer(bearer: String?) {
        require(bearer == null || (bearer.isNotBlank() && bearer.toByteArray().size <= MAX_BEARER_BYTES)) {
            "A2A bearer credential is empty or oversized"
        }
        require(bearer == null || ('\r' !in bearer && '\n' !in bearer)) { "A2A bearer credential is invalid" }
    }

    private fun Response.isJson(): Boolean {
        val type = body.contentType() ?: return false
        return type.type == "application" && (type.subtype == "json" || type.subtype == "a2a+json")
    }

    private fun Response.isSse(): Boolean {
        val type = body.contentType() ?: return false
        return type.type == "text" && type.subtype == "event-stream"
    }

    private enum class Operation(
        val method: String,
        val mutating: Boolean,
    ) {
        SEND("SendMessage", true),
        SEND_STREAM("SendStreamingMessage", true),
        GET("GetTask", false),
        CANCEL("CancelTask", true),
        SUBSCRIBE("SubscribeToTask", false),
    }

    private companion object {
        const val MAX_REQUEST_BYTES = 2 * 1024 * 1024
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        const val MAX_SSE_EVENT_BYTES = 512 * 1024
        const val MAX_BEARER_BYTES = 4_096
        const val BUFFER_SIZE = 8_192
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val A2A_MEDIA_TYPE = "application/a2a+json; charset=utf-8".toMediaType()
    }
}
