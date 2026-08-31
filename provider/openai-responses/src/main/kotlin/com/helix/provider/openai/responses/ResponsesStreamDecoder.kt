package com.helix.provider.openai.responses

import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ToolCallId
import com.helix.provider.api.StreamDecoder
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * OpenAI Responses stream decoder (HXA-022, doc 02 section 6.2, doc 10 section 2.1).
 *
 * Feeds raw HTTP body chunks into an incremental [ResponsesSseParser] and maps the
 * vendor SSE events to the internal [ModelEvent] contract. The Agent Loop never
 * sees vendor JSON.
 *
 * Terminal semantics (HXA-021 stream contract): exactly one of
 * `Completed` / `Refusal` / `Error` terminates the stream.
 * - `response.completed` (status `completed`) → optional [ModelEvent.Usage] then
 *   [ModelEvent.Completed]; the finish reason is `tool_calls` when the response
 *   contained a function call, otherwise `stop`;
 * - `response.failed` / a mid-stream `error` event → [ModelEvent.Error] with the
 *   vendor code mapped in [mapVendorError];
 * - a content-filtered message (`response.output_item.done` with
 *   `incomplete_details.reason = "content_filter"`) primes a pending refusal;
 *   the vendor's following `response.incomplete` (reason `content_filter`) then
 *   emits its [ModelEvent.Usage] and the [ModelEvent.Refusal] terminal (a
 *   legitimate completion, doc 02 section 6.1). A server that sends only the
 *   item-level signal is handled by [finish], which emits the pending refusal
 *   as the terminal. With `max_output_tokens` the response completes with
 *   finish reason `length`;
 * - the stream ending without any terminal event (no termination / connection
 *   dropped) → `Error(PROTOCOL, retryable=true)`;
 * - a handled vendor stream event after the terminal, malformed JSON, or a
 *   missing required field → `Error(PROTOCOL, retryable=false)` and the decoder
 *   stops producing events.
 *
 * Non-2xx HTTP responses are the transport layer's concern (HXA-025): the body
 * of a failed response is not an SSE stream and must not be fed here.
 *
 * Vendor contract violations inside an event are signalled with
 * [ProtocolViolation] and converted into the single `Error(PROTOCOL)` event by
 * [handle]; the decoder itself never throws on vendor data.
 *
 * Suppression is deliberate: the function count is the vendor SSE event
 * vocabulary (ten handled event types) plus the accessor/emit helpers —
 * splitting the mapper across classes adds indirection without reducing the
 * protocol surface.
 */
@Suppress("TooManyFunctions")
public class ResponsesStreamDecoder : StreamDecoder {
    private val parser = ResponsesSseParser()
    private val json = Json { ignoreUnknownKeys = true }
    private var terminalEmitted = false
    private var protocolFailed = false
    private var pendingRefusal = false
    private var hasFunctionCall = false
    private var toolCallCount = 0
    private var failureDetail: String? = null

    /** Diagnostic detail of the protocol failure (exception class / vendor field, no payload). */
    public val failure: String?
        get() = failureDetail

    /** Feed one raw HTTP body chunk; returns the internal events it produced. */
    public override fun feed(chunk: ByteArray): List<ModelEvent> {
        if (protocolFailed) return emptyList()
        val out = ArrayList<ModelEvent>()
        for (event in parser.feed(chunk)) {
            handle(event, out)
            if (protocolFailed) break
        }
        if (parser.isFailed && !protocolFailed) failProtocol(out, "sse: ${parser.failure}")
        return out
    }

    /** The stream ended; flushes the tail and enforces the terminal guard. */
    public override fun finish(): List<ModelEvent> {
        if (protocolFailed) return emptyList()
        val out = ArrayList<ModelEvent>()
        for (event in parser.finish()) {
            handle(event, out)
            if (protocolFailed) break
        }
        if (parser.isFailed && !protocolFailed) {
            failProtocol(out, "sse: ${parser.failure}")
        } else if (!terminalEmitted) {
            if (pendingRefusal) {
                // The vendor signaled the content-filter refusal on the item level
                // only; it is still a legitimate completion.
                terminalEmitted = true
                out += ModelEvent.Refusal("content_filter")
            } else {
                // No termination: the stream ended (or the connection dropped) before
                // response.completed/failed/incomplete or an error event.
                protocolFailed = true
                out += ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = true)
            }
        }
        return out
    }

    private fun handle(
        event: SseEvent,
        out: MutableList<ModelEvent>,
    ) {
        if (protocolFailed || isPostTerminalViolation(event)) {
            failProtocol(out, "event after terminal: ${event.type}")
            return
        }
        try {
            mapEvent(event, out)
        } catch (e: ProtocolViolation) {
            failProtocol(out, e.detail)
        }
    }

    private fun mapEvent(
        event: SseEvent,
        out: MutableList<ModelEvent>,
    ) {
        when (event.type) {
            "response.output_item.added" -> {
                handleOutputItemAdded(event, out)
            }

            "response.output_text.delta" -> {
                handleTextDelta(event, reasoning = false, out = out)
            }

            "response.function_call_arguments.delta" -> {
                handleArgumentsDelta(event, out)
            }

            "response.function_call_arguments.done" -> {
                handleArgumentsDone(event, out)
            }

            "response.reasoning_summary_text.delta" -> {
                handleTextDelta(event, reasoning = true, out = out)
            }

            "response.output_item.done" -> {
                handleOutputItemDone(event)
            }

            "response.completed" -> {
                handleCompleted(event, out)
            }

            "response.failed" -> {
                handleFailed(event, out)
            }

            "response.incomplete" -> {
                handleIncomplete(event, out)
            }

            "error" -> {
                handleErrorEvent(event, out)
            }

            // response.created / response.in_progress / response.content_part.* /
            // ping and any future vendor event: ignored (forward compatible).
            else -> {
                Unit
            }
        }
    }

    private fun isPostTerminalViolation(event: SseEvent): Boolean = terminalEmitted && event.type in HANDLED_TYPES

    private fun parsePayload(event: SseEvent): JsonObject {
        val element =
            try {
                json.parseToJsonElement(event.data)
            } catch (e: SerializationException) {
                throw ProtocolViolation("json: ${e::class.simpleName}")
            }
        val obj = element as? JsonObject
        if (obj == null) throw ProtocolViolation("payload is not a json object: ${event.type}")
        return obj
    }

    /**
     * Required string field: throws [ProtocolViolation] when missing or not a
     * string. An empty value is returned as-is; the caller decides whether
     * empty is legal (e.g. argument deltas may be empty noise, call ids may not).
     */
    private fun requireString(
        obj: JsonObject,
        key: String,
    ): String {
        val value = (obj[key] as? JsonPrimitive)?.contentOrNull
        if (value == null) throw ProtocolViolation("missing required field: $key")
        return value
    }

    private fun requireIndex(obj: JsonObject): Int {
        val value = (obj["output_index"] as? JsonPrimitive)?.longOrNull
        if (value == null || value < 0 || value > MAX_TOOL_INDEX) {
            throw ProtocolViolation("missing or invalid output_index")
        }
        return value.toInt()
    }

    /**
     * ThrowsCount suppression is deliberate: each throw is a distinct vendor
     * contract violation (missing item, empty identifier, charset, fan-out
     * bound) and fail-closed semantics require stopping at the first one.
     */
    @Suppress("ThrowsCount")
    private fun handleOutputItemAdded(
        event: SseEvent,
        out: MutableList<ModelEvent>,
    ) {
        val obj = parsePayload(event)
        val item =
            obj["item"] as? JsonObject
                ?: throw ProtocolViolation("output_item.added: missing item")
        if (stringOf(item["type"]) != "function_call") return
        val callId = requireString(item, "call_id")
        val name = requireString(item, "name")
        val index = requireIndex(obj)
        if (callId.isEmpty() || name.isEmpty()) {
            throw ProtocolViolation("function_call item has an empty call_id or name")
        }
        // A vendor id outside the ToolCallId charset cannot be represented:
        // fail the stream instead of emitting a partial event.
        val id =
            try {
                ToolCallId(callId)
            } catch (e: IllegalArgumentException) {
                throw ProtocolViolation("call id charset: ${e::class.simpleName}")
            }
        toolCallCount++
        if (toolCallCount > MAX_TOOL_CALLS) {
            throw ProtocolViolation("too many function calls in one response")
        }
        hasFunctionCall = true
        out += ModelEvent.ToolCallStarted(index, id, name)
    }

    private fun handleTextDelta(
        event: SseEvent,
        reasoning: Boolean,
        out: MutableList<ModelEvent>,
    ) {
        val obj = parsePayload(event)
        val delta = requireString(obj, "delta")
        if (delta.isEmpty()) return
        out += if (reasoning) ModelEvent.ReasoningDelta(delta) else ModelEvent.TextDelta(delta)
    }

    private fun handleArgumentsDelta(
        event: SseEvent,
        out: MutableList<ModelEvent>,
    ) {
        val obj = parsePayload(event)
        val index = requireIndex(obj)
        val delta = requireString(obj, "delta")
        if (delta.isEmpty()) return
        out += ModelEvent.ToolArgumentsDelta(index, delta)
    }

    private fun handleArgumentsDone(
        event: SseEvent,
        out: MutableList<ModelEvent>,
    ) {
        val obj = parsePayload(event)
        val index = requireIndex(obj)
        out += ModelEvent.ToolCallFinished(index)
    }

    private fun handleOutputItemDone(event: SseEvent) {
        val obj = parsePayload(event)
        val item = obj["item"] as? JsonObject ?: return
        if (stringOf(item["type"]) != "message" || stringOf(item["status"]) != "incomplete") return
        // Prime the refusal; the terminal is emitted by response.incomplete
        // (carrying the usage) or by finish() when the vendor sends no
        // response-level event.
        if (stringOf((item["incomplete_details"] as? JsonObject)?.get("reason")) == "content_filter") {
            pendingRefusal = true
        }
    }

    private fun handleCompleted(
        event: SseEvent,
        out: MutableList<ModelEvent>,
    ) {
        val obj = parsePayload(event)
        val response =
            obj["response"] as? JsonObject
                ?: throw ProtocolViolation("response.completed: missing response object")
        if (stringOf(response["status"]) != "completed") {
            throw ProtocolViolation("response.completed with unexpected status")
        }
        emitUsage(response, out)
        terminalEmitted = true
        out += ModelEvent.Completed(if (hasFunctionCall) FINISH_TOOL_CALLS else FINISH_STOP)
    }

    private fun handleFailed(
        event: SseEvent,
        out: MutableList<ModelEvent>,
    ) {
        val obj = parsePayload(event)
        val response =
            obj["response"] as? JsonObject
                ?: throw ProtocolViolation("response.failed: missing response object")
        emitError(stringOf((response["error"] as? JsonObject)?.get("code")), out)
    }

    private fun handleIncomplete(
        event: SseEvent,
        out: MutableList<ModelEvent>,
    ) {
        val obj = parsePayload(event)
        val response =
            obj["response"] as? JsonObject
                ?: throw ProtocolViolation("response.incomplete: missing response object")
        emitUsage(response, out)
        terminalEmitted = true
        // The response-level reason is authoritative over the item-level prime.
        pendingRefusal = false
        when (stringOf((response["incomplete_details"] as? JsonObject)?.get("reason"))) {
            "content_filter" -> out += ModelEvent.Refusal("content_filter")
            "max_output_tokens" -> out += ModelEvent.Completed(FINISH_LENGTH)
            else -> throw ProtocolViolation("response.incomplete with unknown reason")
        }
    }

    private fun handleErrorEvent(
        event: SseEvent,
        out: MutableList<ModelEvent>,
    ) {
        val obj = parsePayload(event)
        emitError(stringOf(obj["code"]), out)
    }

    private fun emitError(
        code: String?,
        out: MutableList<ModelEvent>,
    ) {
        terminalEmitted = true
        val (error, retryable) = mapVendorError(code)
        out += ModelEvent.Error(error, retryable)
    }

    private fun emitUsage(
        response: JsonObject,
        out: MutableList<ModelEvent>,
    ) {
        val usage = response["usage"] as? JsonObject ?: return
        val input = (usage["input_tokens"] as? JsonPrimitive)?.longOrNull
        val output = (usage["output_tokens"] as? JsonPrimitive)?.longOrNull
        out += ModelEvent.Usage(input, output)
    }

    private fun failProtocol(
        out: MutableList<ModelEvent>,
        detail: String,
    ) {
        if (protocolFailed) return
        out += ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = false)
        protocolFailed = true
        failureDetail = detail
    }

    private fun stringOf(element: JsonElement?): String? = (element as? JsonPrimitive)?.contentOrNull

    /**
     * Vendor error code → internal [ModelErrorCode]. Unknown codes fail closed:
     * `SERVER_ERROR` but not retryable (the Agent Loop must not blindly resend).
     */
    private fun mapVendorError(code: String?): Pair<ModelErrorCode, Boolean> =
        when (code) {
            "rate_limit_exceeded" -> ModelErrorCode.RATE_LIMITED to true
            "server_error" -> ModelErrorCode.SERVER_ERROR to true
            "content_filter" -> ModelErrorCode.CONTENT_FILTER to false
            "invalid_request_error" -> ModelErrorCode.PROTOCOL to false
            else -> ModelErrorCode.SERVER_ERROR to false
        }

    private companion object {
        const val FINISH_STOP = "stop"
        const val FINISH_LENGTH = "length"
        const val FINISH_TOOL_CALLS = "tool_calls"
        const val MAX_TOOL_CALLS = 32 // same bound as core:agent ModelTerminal.ToolCalls
        const val MAX_TOOL_INDEX = 1_024

        val HANDLED_TYPES =
            setOf(
                "response.output_item.added",
                "response.output_text.delta",
                "response.function_call_arguments.delta",
                "response.function_call_arguments.done",
                "response.reasoning_summary_text.delta",
                "response.output_item.done",
                "response.completed",
                "response.failed",
                "response.incomplete",
                "error",
            )
    }
}

/** Internal control-flow marker: a vendor stream contract violation (never escapes [ResponsesStreamDecoder]). */
private class ProtocolViolation(
    val detail: String,
) : RuntimeException(detail)
