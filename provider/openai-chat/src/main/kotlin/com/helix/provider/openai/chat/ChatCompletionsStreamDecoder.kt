package com.helix.provider.openai.chat

import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ToolCallId
import com.helix.provider.api.StreamDecoder
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * OpenAI Chat Completions stream decoder (HXA-023, doc 02 section 6.2,
 * doc 10 section 2.1). Independent implementation: this adapter never falls
 * back to the Responses protocol after a failure — the protocol is fixed by
 * the provider configuration (`OPENAI_CHAT_COMPLETIONS`).
 *
 * Feeds raw HTTP body chunks into an incremental [ChatSseReader] and maps the
 * `chat.completion.chunk` payloads to the internal [ModelEvent] contract. The
 * Agent Loop never sees vendor JSON.
 *
 * Chunk semantics (vendor-documented shapes):
 * - `choices[0].delta.content` (non-empty) → [ModelEvent.TextDelta];
 * - `choices[0].delta.tool_calls[]` keyed by the vendor fragment `index`:
 *   a fragment carrying `id` + `function.name` starts the call
 *   ([ModelEvent.ToolCallStarted]); later fragments for the same index carry
 *   incremental `function.arguments` ([ModelEvent.ToolArgumentsDelta]); a
 *   duplicate start or an arguments fragment for an unknown index fails the
 *   stream;
 * - `choices[0].finish_reason`: `stop` → [ModelEvent.Completed](`stop`),
 *   `length` → Completed(`length`), `tool_calls` → [ModelEvent.ToolCallFinished]
 *   for every open index (ascending) then Completed(`tool_calls`),
 *   `content_filter` → [ModelEvent.Refusal] (a legitimate completion; open
 *   calls are not closed — the refusal is authoritative); `stop`/`length`
 *   with open calls, `tool_calls` with none, and any unknown reason fail the
 *   stream;
 * - a chunk with an empty/absent `choices` and a `usage` object (sent after
 *   the finish chunk when the request sets `stream_options.include_usage`) →
 *   [ModelEvent.Usage] — accepted even after the terminal (the documented
 *   vendor order);
 * - the `[DONE]` data payload marks the end of the vendor stream; it is not
 *   itself a terminal event.
 *
 * Terminal guard (HXA-021 stream contract): exactly one of `Completed` /
 * `Refusal` / `Error` terminates the stream. A non-usage chunk after the
 * terminal, malformed JSON, an out-of-range choice index, or a stream ending
 * without any terminal (including a `[DONE]` without a finish chunk) —
 * retryable — map to [ModelEvent.Error] with `PROTOCOL` (`retryable=true`
 * only for the no-termination case; everything else is `retryable=false` and
 * stops production). Non-2xx HTTP failures are the transport layer's concern
 * (HXA-025) and are not SSE.
 *
 * Suppression is deliberate: the function count is the vendor chunk vocabulary
 * plus the accessor/emit helpers, and the fail-closed mapping style is one
 * early return per vendor contract violation.
 */
@Suppress("TooManyFunctions", "ReturnCount")
public class ChatCompletionsStreamDecoder : StreamDecoder {
    private val reader = ChatSseReader()
    private val json = Json { ignoreUnknownKeys = true }
    private var terminalEmitted = false
    private var protocolFailed = false
    private var openToolCalls = LinkedHashMap<Int, OpenToolCall>()
    private var toolCallCount = 0
    private var failureDetail: String? = null

    /** Diagnostic detail of the protocol failure (exception class / vendor field, no payload). */
    public val failure: String?
        get() = failureDetail

    /** Feed one raw HTTP body chunk; returns the internal events it produced. */
    public override fun feed(chunk: ByteArray): List<ModelEvent> {
        if (protocolFailed) return emptyList()
        val out = ArrayList<ModelEvent>()
        for (payload in reader.feed(chunk)) {
            handle(payload, out)
            if (protocolFailed) break
        }
        if (reader.isFailed && !protocolFailed) failProtocol(out, "sse: ${reader.failure}")
        return out
    }

    /** The stream ended; flushes the tail and enforces the terminal guard. */
    public override fun finish(): List<ModelEvent> {
        if (protocolFailed) return emptyList()
        val out = ArrayList<ModelEvent>()
        for (payload in reader.finish()) {
            handle(payload, out)
            if (protocolFailed) break
        }
        if (reader.isFailed && !protocolFailed) {
            failProtocol(out, "sse: ${reader.failure}")
        } else if (!terminalEmitted) {
            // No termination: the stream ended (or the connection dropped) before
            // any finish chunk — possibly after a bare [DONE]. Connection-level
            // failure: a retry may succeed.
            protocolFailed = true
            out += ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = true)
        }
        return out
    }

    private fun handle(
        payload: String,
        out: MutableList<ModelEvent>,
    ) {
        if (payload == DONE_TOKEN) return // vendor stream terminator, not an event
        if (protocolFailed) return
        try {
            mapPayload(payload, out)
        } catch (e: ProtocolViolation) {
            failProtocol(out, e.detail)
        }
    }

    /**
     * ThrowsCount suppression is deliberate: each throw is a distinct vendor
     * contract violation (post-terminal chunk, error object, parallel choice
     * index) and fail-closed semantics require stopping at the first one.
     */
    @Suppress("ThrowsCount")
    private fun mapPayload(
        payload: String,
        out: MutableList<ModelEvent>,
    ) {
        val chunk = parseChunk(payload)
        if (terminalEmitted) {
            // The only chunk the vendor sends after the finish chunk is the
            // usage chunk (empty choices + usage); everything else is a
            // contract violation.
            if (hasChunkContent(chunk)) throw ProtocolViolation("chunk after terminal")
            handleUsage(chunk, out)
            return
        }
        if ((chunk["error"] as? JsonObject) != null) {
            // No documented mid-stream error event: an error object is a
            // protocol contract violation (non-2xx is the transport's job).
            throw ProtocolViolation("error object in stream chunk")
        }
        val choices = chunk["choices"]?.jsonArray
        if (choices != null && choices.isNotEmpty()) {
            val choice = choices[0].jsonObject
            // We never request n > 1: a non-zero candidate index is unsupported.
            val index = (choice["index"] as? JsonPrimitive)?.longOrNull ?: 0L
            if (index != 0L) throw ProtocolViolation("unsupported parallel choice index")
            handleDelta(choice["delta"] as? JsonObject, out)
            handleUsage(chunk, out)
            handleFinishReason((choice["finish_reason"] as? JsonPrimitive)?.contentOrNull, out)
        } else {
            handleUsage(chunk, out)
        }
    }

    private fun parseChunk(payload: String): JsonObject {
        val element =
            try {
                json.parseToJsonElement(payload)
            } catch (e: SerializationException) {
                throw ProtocolViolation("json: ${e::class.simpleName}")
            }
        val obj = element as? JsonObject
        if (obj == null) throw ProtocolViolation("chunk is not a json object")
        return obj
    }

    private fun hasChunkContent(chunk: JsonObject): Boolean {
        val choices = chunk["choices"]?.jsonArray
        return choices != null && choices.isNotEmpty()
    }

    private fun handleDelta(
        delta: JsonObject?,
        out: MutableList<ModelEvent>,
    ) {
        if (delta == null) return
        val content = (delta["content"] as? JsonPrimitive)?.contentOrNull
        if (content != null && content.isNotEmpty()) out += ModelEvent.TextDelta(content)
        val fragments = delta["tool_calls"]?.jsonArray
        if (fragments != null) {
            for (fragment in fragments) handleToolCallFragment(fragment.jsonObject, out)
        }
    }

    /**
     * ThrowsCount suppression is deliberate: each throw is a distinct vendor
     * contract violation (unknown index, duplicate start, empty identifier,
     * charset, fan-out bound) and fail-closed semantics require stopping at
     * the first one.
     */
    @Suppress("ThrowsCount")
    private fun handleToolCallFragment(
        fragment: JsonObject,
        out: MutableList<ModelEvent>,
    ) {
        val index = (fragment["index"] as? JsonPrimitive)?.longOrNull
        if (index == null || index < 0L || index > MAX_TOOL_INDEX) {
            throw ProtocolViolation("tool call fragment with invalid index")
        }
        val i = index.toInt()
        val id = (fragment["id"] as? JsonPrimitive)?.contentOrNull
        val function = fragment["function"] as? JsonObject
        if (id != null) {
            if (openToolCalls.containsKey(i)) {
                throw ProtocolViolation("duplicate tool call start for index $i")
            }
            val name = (function?.get("name") as? JsonPrimitive)?.contentOrNull
            if (id.isEmpty() || name.isNullOrEmpty()) {
                throw ProtocolViolation("tool call fragment missing id/name")
            }
            // A vendor id outside the ToolCallId charset cannot be represented:
            // fail the stream instead of emitting a partial event.
            val toolCallId =
                try {
                    ToolCallId(id)
                } catch (e: IllegalArgumentException) {
                    throw ProtocolViolation("call id charset: ${e::class.simpleName}")
                }
            toolCallCount++
            if (toolCallCount > MAX_TOOL_CALLS) {
                throw ProtocolViolation("too many function calls in one response")
            }
            openToolCalls[i] = OpenToolCall(toolCallId, name)
            out += ModelEvent.ToolCallStarted(i, toolCallId, name)
        } else {
            if (!openToolCalls.containsKey(i)) {
                throw ProtocolViolation("arguments fragment for unknown tool call index $i")
            }
            val arguments = (function?.get("arguments") as? JsonPrimitive)?.contentOrNull
            if (arguments != null && arguments.isNotEmpty()) {
                out += ModelEvent.ToolArgumentsDelta(i, arguments)
            }
        }
    }

    /**
     * ThrowsCount suppression is deliberate: each throw is a distinct vendor
     * contract violation (open calls at stop/length, no calls at tool_calls,
     * unknown reason) and fail-closed semantics require stopping at the first
     * one.
     */
    @Suppress("ThrowsCount")
    private fun handleFinishReason(
        reason: String?,
        out: MutableList<ModelEvent>,
    ) {
        if (reason == null) return
        when (reason) {
            FINISH_STOP, FINISH_LENGTH -> {
                if (openToolCalls.isNotEmpty()) {
                    throw ProtocolViolation("finish reason $reason with open tool calls")
                }
                terminalEmitted = true
                out += ModelEvent.Completed(reason)
            }

            FINISH_TOOL_CALLS -> {
                if (openToolCalls.isEmpty()) {
                    throw ProtocolViolation("finish reason tool_calls with no open tool calls")
                }
                closeOpenToolCalls(out)
                terminalEmitted = true
                out += ModelEvent.Completed(FINISH_TOOL_CALLS)
            }

            FINISH_CONTENT_FILTER -> {
                terminalEmitted = true
                out += ModelEvent.Refusal("content_filter")
            }

            else -> {
                throw ProtocolViolation("unknown finish reason: $reason")
            }
        }
    }

    private fun closeOpenToolCalls(out: MutableList<ModelEvent>) {
        openToolCalls.keys.sorted().forEach { out += ModelEvent.ToolCallFinished(it) }
        openToolCalls.clear()
    }

    private fun handleUsage(
        chunk: JsonObject,
        out: MutableList<ModelEvent>,
    ) {
        val usage = chunk["usage"] as? JsonObject ?: return
        val input = (usage["prompt_tokens"] as? JsonPrimitive)?.longOrNull
        val output = (usage["completion_tokens"] as? JsonPrimitive)?.longOrNull
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

    private class OpenToolCall(
        val id: ToolCallId,
        val name: String,
    )

    private companion object {
        const val DONE_TOKEN = "[DONE]"
        const val FINISH_STOP = "stop"
        const val FINISH_LENGTH = "length"
        const val FINISH_TOOL_CALLS = "tool_calls"
        const val FINISH_CONTENT_FILTER = "content_filter"
        const val MAX_TOOL_CALLS = 32 // same bound as core:agent ModelTerminal.ToolCalls
        const val MAX_TOOL_INDEX = 1_024
    }
}

/** Internal control-flow marker: a vendor stream contract violation (never escapes [ChatCompletionsStreamDecoder]). */
private class ProtocolViolation(
    val detail: String,
) : RuntimeException(detail)
