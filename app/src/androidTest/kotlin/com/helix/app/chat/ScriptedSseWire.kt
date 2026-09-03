package com.helix.app.chat

import com.helix.provider.api.wire.WireBody
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse

/**
 * HXA-056: the scripted OpenAI Chat Completions SSE wire for the attachment E2E fixtures.
 * It records every outgoing request (the GOLDEN-request assertions run against the recorded
 * bodies) and replays canned SSE streams in call order — the REAL production adapter parses
 * the stream, so the E2E exercises the production path end to end (ChatService →
 * ProviderService → protocol adapter → wire) with a deterministic OFFLINE model. The model
 * never runs; the streams are fixed fixtures (roadmap HXA-056: fixture-based E2E; the real
 * vision endpoint smoke is a separate, environment-documented step and never substitutes
 * these fixtures).
 */
class ScriptedSseWire {
    /** One recorded exchange (body decoded as UTF-8 for JSON assertions). */
    data class RecordedRequest(
        val url: String,
        val headers: Map<String, String>,
        val body: String,
    )

    val requests = ArrayList<RecordedRequest>()

    /** One canned response per model call, in call order; running out fails the call. */
    private val canned = ArrayList<WireResponse>()

    /** Appends the responses for the NEXT model calls (call order). */
    fun script(vararg responses: WireResponse) {
        canned += responses.toList()
    }

    val callCount: Int
        get() = requests.size

    val allBodies: List<String>
        get() = requests.map { it.body }

    val lastRequestBody: String
        get() = requests.lastOrNull()?.body ?: error("scripted wire: no request recorded")

    val wireClient: WireClient =
        object : WireClient {
            override suspend fun open(request: WireRequest): WireResponse {
                val body = request.body?.toString(Charsets.UTF_8).orEmpty()
                requests += RecordedRequest(request.url, request.headers, body)
                return canned.getOrNull(requests.size - 1)
                    ?: error("scripted wire: no canned response for call $requests.size")
            }
        }
}

/** A 200 text/event-stream response over [stream]. */
fun sseResponse(stream: String): WireResponse =
    WireResponse(
        200,
        mapOf("content-type" to listOf("text/event-stream")),
        SseBody(stream.toByteArray(Charsets.UTF_8)),
    )

/**
 * A non-2xx HTTP failure (HXA-023 error mapping): the adapter emits ONE terminal
 * [ModelEvent.Error] from [mapHttpStatus] — the body is diagnostic-only and never decoded.
 */
fun httpErrorResponse(status: Int): WireResponse =
    WireResponse(
        status,
        mapOf("content-type" to listOf("application/json")),
        SseBody("{\"error\":\"scripted failure\"}".toByteArray(Charsets.UTF_8)),
    )

/** One-shot body: the whole stream as a single chunk (the SSE decoder is chunk-agnostic). */
class SseBody(
    private val data: ByteArray,
) : WireBody {
    override suspend fun bytes(): ByteArray = data.copyOf()

    override suspend fun forEachChunk(onChunk: suspend (ByteArray) -> Boolean) {
        if (data.isNotEmpty() && !onChunk(data.copyOf())) return
    }

    override fun close() = Unit
}

private fun sseChunk(json: String): String = "data: $json\n\n"

private const val DONE_SSE = "data: [DONE]\n\n"

/** A plain streaming text answer: two deltas, stop, usage, DONE (HXA-023 fixture shape). */
fun textAnswerStream(text: String): String {
    val half = text.length / 2
    val first = text.substring(0, half)
    val second = text.substring(half)
    return sseChunk(
        "{\"id\":\"chatcmpl-e2e\",\"object\":\"chat.completion.chunk\"," +
            "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":null}," +
            "\"finish_reason\":null}]}",
    ) +
        sseChunk(
            "{\"id\":\"chatcmpl-e2e\",\"choices\":[{\"index\":0," +
                "\"delta\":{\"content\":${jsonString(first)}},\"finish_reason\":null}]}",
        ) +
        sseChunk(
            "{\"id\":\"chatcmpl-e2e\",\"choices\":[{\"index\":0," +
                "\"delta\":{\"content\":${jsonString(second)}},\"finish_reason\":null}]}",
        ) +
        sseChunk(
            "{\"id\":\"chatcmpl-e2e\",\"choices\":[{\"index\":0," +
                "\"delta\":{},\"finish_reason\":\"stop\"}]}",
        ) +
        sseChunk(
            "{\"id\":\"chatcmpl-e2e\",\"choices\":[]," +
                "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":4,\"total_tokens\":14}}",
        ) +
        DONE_SSE
}

/**
 * A tool-call answer (Ollama shape: the COMPLETE arguments ride in the start fragment)
 * followed by DONE — the second call in the script is the post-tool answer.
 */
fun toolCallStream(
    callId: String,
    name: String,
    arguments: String,
): String =
    sseChunk(
        "{\"id\":\"chatcmpl-e2e\",\"object\":\"chat.completion.chunk\"," +
            "\"choices\":[{\"index\":0," +
            "\"delta\":{\"role\":\"assistant\",\"content\":\"\"," +
            "\"tool_calls\":[{\"id\":\"$callId\",\"index\":0," +
            "\"type\":\"function\"," +
            "\"function\":{\"name\":$name,\"arguments\":${jsonString(arguments)}}}]}," +
            "\"finish_reason\":null}]}",
    ) +
        sseChunk(
            "{\"id\":\"chatcmpl-e2e\",\"choices\":[{\"index\":0," +
                "\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
        ) +
        DONE_SSE

/** Minimal JSON string escaping (the fixtures never need beyond quotes/backslashes/control). */
private fun jsonString(value: String): String {
    val sb = StringBuilder("\"")
    for (c in value) {
        when (c) {
            '"' -> {
                sb.append("\\\"")
            }

            '\\' -> {
                sb.append("\\\\")
            }

            '\n' -> {
                sb.append("\\n")
            }

            '\r' -> {
                sb.append("\\r")
            }

            '\t' -> {
                sb.append("\\t")
            }

            else -> {
                if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
    }
    return sb.append('"').toString()
}

/**
 * The CANCEL fixture: a 200 SSE body that emits [firstChunk] and then suspends until the
 * collector is cancelled (the model that never answers). The turn's stop() cancels the
 * collection; the body's suspension is cancelled with it, so no thread leaks.
 */
fun stalledResponse(firstChunk: String): WireResponse {
    val body =
        object : WireBody {
            override suspend fun bytes(): ByteArray = firstChunk.toByteArray(Charsets.UTF_8)

            override suspend fun forEachChunk(onChunk: suspend (ByteArray) -> Boolean) {
                if (!onChunk(firstChunk.toByteArray(Charsets.UTF_8).copyOf())) return
                kotlinx.coroutines.suspendCancellableCoroutine<Unit> { } // cancelled with the collector
            }

            override fun close() = Unit
        }
    return WireResponse(200, mapOf("content-type" to listOf("text/event-stream")), body)
}
