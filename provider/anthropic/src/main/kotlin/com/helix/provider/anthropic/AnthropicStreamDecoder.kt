package com.helix.provider.anthropic

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
 * Anthropic Messages stream decoder (HXA-024, doc 02 section 6.2,
 * doc 10 section 2.1). Independent implementation: this adapter never falls
 * back to any OpenAI protocol after a failure — the protocol is fixed by the
 * provider configuration (`ANTHROPIC_MESSAGES`).
 *
 * Feeds raw HTTP body chunks into an incremental [AnthropicSseReader] and
 * maps the typed Messages events to the internal [ModelEvent] contract. The
 * Agent Loop never sees vendor JSON.
 *
 * Event semantics (vendor-documented shapes):
 * - `message_start` → the `message.usage.input_tokens` are remembered for the
 *   final [ModelEvent.Usage] (emitted with the terminal, like the sibling
 *   adapters);
 * - `content_block_start`: a `tool_use` block (with `id` + `name`) emits
 *   [ModelEvent.ToolCallStarted] keyed by the content block `index`; `text`
 *   and `thinking` blocks are tracked silently; unknown block kinds are
 *   ignored (forward compatible) together with their deltas;
 * - `content_block_delta`: `text_delta` → [ModelEvent.TextDelta],
 *   `thinking_delta` → [ModelEvent.ReasoningDelta], `input_json_delta` →
 *   [ModelEvent.ToolArgumentsDelta] (the vendor keys argument fragments by
 *   the block index); a delta for an unknown or already stopped block index,
 *   or a delta kind that does not match the block kind, fails the stream
 *   (ordering constraint);
 * - `content_block_stop`: a stopped `tool_use` block emits
 *   [ModelEvent.ToolCallFinished];
 * - `message_delta` is the terminal chunk: all content blocks must already be
 *   stopped; it emits [ModelEvent.Usage] (input from `message_start`, output
 *   from this event; a missing value stays null) and then the terminal from
 *   `stop_reason`: `end_turn`/`stop_sequence` → [ModelEvent.Completed](`stop`),
 *   `max_tokens` → Completed(`length`), `tool_use` → Completed(`tool_calls`),
 *   `refusal` → [ModelEvent.Refusal] (a legitimate completion), any unknown
 *   reason fails the stream;
 * - `message_stop` marks the end of the vendor stream (not a terminal event);
 *   a stream ending with no terminal — with or without `message_stop` — maps
 *   to a retryable [ModelEvent.Error] at [finish];
 * - `ping` is ignored; an `error` event maps [ModelErrorCode] in
 *   [mapVendorError] (unknown types fail closed and are not retryable).
 *
 * Terminal guard (HXA-021 stream contract): exactly one of `Completed` /
 * `Refusal` / `Error` terminates the stream. A handled event after the
 * terminal, a handled event after `message_stop`, malformed JSON, or an
 * out-of-range block index map to [ModelEvent.Error] with `PROTOCOL`
 * (`retryable=false`, production stops); only the no-termination case is
 * `retryable=true`. Non-2xx HTTP failures are the transport layer's concern
 * (HXA-025) and are not SSE.
 *
 * Suppressions are deliberate: the function count is the vendor event
 * vocabulary plus the accessor/emit helpers, and the fail-closed mapping
 * style is one early return per vendor contract violation.
 */
@Suppress("TooManyFunctions", "ReturnCount")
public class AnthropicStreamDecoder : StreamDecoder {
    private val reader = AnthropicSseReader()
    private val json = Json { ignoreUnknownKeys = true }
    private var terminalEmitted = false
    private var protocolFailed = false
    private var streamEnded = false
    private var inputTokens: Long? = null
    private var openBlocks = LinkedHashMap<Int, BlockKind>()
    private var toolCallCount = 0
    private var failureDetail: String? = null

    /** Diagnostic detail of the protocol failure (exception class / vendor field, no payload). */
    public val failure: String?
        get() = failureDetail

    /** Feed one raw HTTP body chunk; returns the internal events it produced. */
    public override fun feed(chunk: ByteArray): List<ModelEvent> {
        if (protocolFailed) return emptyList()
        val out = ArrayList<ModelEvent>()
        for (event in reader.feed(chunk)) {
            handle(event, out)
            if (protocolFailed) break
        }
        if (reader.isFailed && !protocolFailed) failProtocol(out, "sse: ${reader.failure}")
        return out
    }

    /** The stream ended; flushes the tail and enforces the terminal guard. */
    public override fun finish(): List<ModelEvent> {
        if (protocolFailed) return emptyList()
        val out = ArrayList<ModelEvent>()
        for (event in reader.finish()) {
            handle(event, out)
            if (protocolFailed) break
        }
        if (reader.isFailed && !protocolFailed) {
            failProtocol(out, "sse: ${reader.failure}")
        } else if (!terminalEmitted) {
            // No termination: the stream ended (or the connection dropped) before
            // message_delta or an error event — possibly after a bare message_stop.
            protocolFailed = true
            out += ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = true)
        }
        return out
    }

    private fun handle(
        event: AnthropicSseEvent,
        out: MutableList<ModelEvent>,
    ) {
        if (terminalEmitted) {
            // The vendor's normal order is message_delta (terminal) followed by
            // message_stop; only a re-opened content stream is a violation.
            if (event.type in HANDLED_TYPES && event.type != "message_stop") {
                failProtocol(out, "event after terminal: ${event.type}")
                return
            }
            return
        }
        if (protocolFailed) return
        try {
            mapEvent(event, out)
        } catch (e: ProtocolViolation) {
            failProtocol(out, e.detail)
        }
    }

    private fun mapEvent(
        event: AnthropicSseEvent,
        out: MutableList<ModelEvent>,
    ) {
        if (streamEnded && event.type in HANDLED_TYPES) {
            throw ProtocolViolation("event after message_stop: ${event.type}")
        }
        when (event.type) {
            "message_start" -> handleMessageStart(event)

            "content_block_start" -> handleBlockStart(event, out)

            "content_block_delta" -> handleBlockDelta(event, out)

            "content_block_stop" -> handleBlockStop(event, out)

            "message_delta" -> handleMessageDelta(event, out)

            "message_stop" -> streamEnded = true

            "error" -> handleStreamError(event, out)

            // ping and any future vendor event: ignored (forward compatible).
            else -> Unit
        }
    }

    private fun parsePayload(event: AnthropicSseEvent): JsonObject {
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

    private fun handleMessageStart(event: AnthropicSseEvent) {
        val obj = parsePayload(event)
        val message =
            obj["message"] as? JsonObject
                ?: throw ProtocolViolation("message_start: missing message object")
        val usage = message["usage"] as? JsonObject ?: return
        inputTokens = (usage["input_tokens"] as? JsonPrimitive)?.longOrNull
        // Nothing is emitted: the Usage event travels with the terminal chunk.
    }

    private fun handleBlockStart(
        event: AnthropicSseEvent,
        out: MutableList<ModelEvent>,
    ) {
        val obj = parsePayload(event)
        val i = requireBlockIndex(obj)
        if (openBlocks.containsKey(i)) {
            throw ProtocolViolation("duplicate content_block_start for index $i")
        }
        val block =
            obj["content_block"] as? JsonObject
                ?: throw ProtocolViolation("content_block_start: missing content_block")
        when (stringOf(block["type"])) {
            "text" -> openBlocks[i] = BlockKind.TEXT

            "thinking" -> openBlocks[i] = BlockKind.THINKING

            "tool_use" -> startToolBlock(block, i, out)

            // Unknown block kinds (redacted_thinking, future kinds) are
            // ignored (forward compatible); their deltas and stop are
            // tracked but produce no events.
            else -> openBlocks[i] = BlockKind.IGNORED
        }
    }

    /**
     * ThrowsCount suppression is deliberate: each throw is a distinct vendor
     * contract violation (empty identifier, charset, fan-out bound) and
     * fail-closed semantics require stopping at the first one.
     */
    @Suppress("ThrowsCount")
    private fun startToolBlock(
        block: JsonObject,
        i: Int,
        out: MutableList<ModelEvent>,
    ) {
        val id = requireString(block, "id")
        val name = requireString(block, "name")
        if (id.isEmpty() || name.isEmpty()) {
            throw ProtocolViolation("tool_use block with empty id/name")
        }
        // A vendor id outside the ToolCallId charset cannot be represented:
        // fail the stream instead of a partial event.
        val toolCallId =
            try {
                ToolCallId(id)
            } catch (e: IllegalArgumentException) {
                throw ProtocolViolation("call id charset: ${e::class.simpleName}")
            }
        toolCallCount++
        if (toolCallCount > MAX_TOOL_CALLS) {
            throw ProtocolViolation("too many tool_use blocks in one response")
        }
        openBlocks[i] = BlockKind.TOOL
        out += ModelEvent.ToolCallStarted(i, toolCallId, name)
    }

    private fun requireBlockIndex(obj: JsonObject): Int {
        val index = (obj["index"] as? JsonPrimitive)?.longOrNull
        if (index == null || index < 0L || index > MAX_BLOCK_INDEX) {
            throw ProtocolViolation("content_block event with invalid index")
        }
        return index.toInt()
    }

    private fun handleBlockDelta(
        event: AnthropicSseEvent,
        out: MutableList<ModelEvent>,
    ) {
        val obj = parsePayload(event)
        val i = requireBlockIndex(obj)
        val kind = openBlocks[i] ?: throw ProtocolViolation("delta for unknown block index $i")
        if (kind == BlockKind.IGNORED) return // unknown block kinds are ignored wholesale
        val delta =
            obj["delta"] as? JsonObject
                ?: throw ProtocolViolation("content_block_delta: missing delta")
        when (stringOf(delta["type"])) {
            "text_delta" -> emitTextDelta(kind, i, delta, out)

            "thinking_delta" -> emitThinkingDelta(kind, i, delta, out)

            "input_json_delta" -> emitInputJsonDelta(kind, i, delta, out)

            // signature_delta (thinking safety) and unknown delta kinds:
            // ignored (forward compatible).
            else -> Unit
        }
    }

    private fun emitTextDelta(
        kind: BlockKind,
        i: Int,
        delta: JsonObject,
        out: MutableList<ModelEvent>,
    ) {
        if (kind != BlockKind.TEXT) {
            throw ProtocolViolation("text_delta on a non-text block $i")
        }
        val text = requireString(delta, "text")
        if (text.isNotEmpty()) out += ModelEvent.TextDelta(text)
    }

    private fun emitThinkingDelta(
        kind: BlockKind,
        i: Int,
        delta: JsonObject,
        out: MutableList<ModelEvent>,
    ) {
        if (kind != BlockKind.THINKING) {
            throw ProtocolViolation("thinking_delta on a non-thinking block $i")
        }
        // The vendor field is `thinking`, not `text`.
        val text = requireString(delta, "thinking")
        if (text.isNotEmpty()) out += ModelEvent.ReasoningDelta(text)
    }

    private fun emitInputJsonDelta(
        kind: BlockKind,
        i: Int,
        delta: JsonObject,
        out: MutableList<ModelEvent>,
    ) {
        if (kind != BlockKind.TOOL) {
            throw ProtocolViolation("input_json_delta on a non-tool block $i")
        }
        val fragment = requireString(delta, "partial_json")
        if (fragment.isNotEmpty()) out += ModelEvent.ToolArgumentsDelta(i, fragment)
    }

    private fun handleBlockStop(
        event: AnthropicSseEvent,
        out: MutableList<ModelEvent>,
    ) {
        val obj = parsePayload(event)
        val index = (obj["index"] as? JsonPrimitive)?.longOrNull
        if (index == null || index < 0L || index > MAX_BLOCK_INDEX) {
            throw ProtocolViolation("content_block_stop with invalid index")
        }
        val i = index.toInt()
        val kind = openBlocks.remove(i) ?: throw ProtocolViolation("stop for unknown block index $i")
        if (kind == BlockKind.TOOL) out += ModelEvent.ToolCallFinished(i)
    }

    /**
     * ThrowsCount suppression is deliberate: each throw is a distinct vendor
     * contract violation (open blocks at the terminal, missing stop_reason,
     * unknown stop reason) and fail-closed semantics require stopping at the
     * first one — no partial Usage event may leak from a malformed terminal.
     */
    @Suppress("ThrowsCount")
    private fun handleMessageDelta(
        event: AnthropicSseEvent,
        out: MutableList<ModelEvent>,
    ) {
        if (openBlocks.isNotEmpty()) {
            throw ProtocolViolation("message_delta with open content blocks")
        }
        val obj = parsePayload(event)
        // Validate the reason before emitting anything: a malformed terminal
        // must not leak a partial Usage event (fail closed).
        val reason = stringOf((obj["delta"] as? JsonObject)?.get("stop_reason"))
        if (reason == null) throw ProtocolViolation("message_delta: missing stop_reason")
        val terminal =
            when (reason) {
                "end_turn", "stop_sequence" -> ModelEvent.Completed("stop")
                "max_tokens" -> ModelEvent.Completed("length")
                "tool_use" -> ModelEvent.Completed("tool_calls")
                "refusal" -> ModelEvent.Refusal("refusal")
                else -> throw ProtocolViolation("unknown stop reason: $reason")
            }
        val output = (obj["usage"] as? JsonObject)?.get("output_tokens")
        out += ModelEvent.Usage(inputTokens, (output as? JsonPrimitive)?.longOrNull)
        emitTerminal(out, terminal)
    }

    private fun handleStreamError(
        event: AnthropicSseEvent,
        out: MutableList<ModelEvent>,
    ) {
        val obj = parsePayload(event)
        val type = stringOf((obj["error"] as? JsonObject)?.get("type"))
        emitError(type, out)
    }

    private fun emitTerminal(
        out: MutableList<ModelEvent>,
        terminal: ModelEvent,
    ) {
        terminalEmitted = true
        out += terminal
    }

    private fun emitError(
        type: String?,
        out: MutableList<ModelEvent>,
    ) {
        terminalEmitted = true
        val (error, retryable) = mapVendorError(type)
        out += ModelEvent.Error(error, retryable)
    }

    private fun requireString(
        obj: JsonObject,
        key: String,
    ): String =
        (obj[key] as? JsonPrimitive)?.contentOrNull
            ?: throw ProtocolViolation("missing required field: $key")

    private fun stringOf(element: JsonElement?): String? = (element as? JsonPrimitive)?.contentOrNull

    private fun failProtocol(
        out: MutableList<ModelEvent>,
        detail: String,
    ) {
        if (protocolFailed) return
        out += ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = false)
        protocolFailed = true
        failureDetail = detail
    }

    /**
     * Vendor stream error type → internal [ModelErrorCode]. Unknown types fail
     * closed: `SERVER_ERROR` but not retryable (the Agent Loop must not
     * blindly resend).
     */
    private fun mapVendorError(type: String?): Pair<ModelErrorCode, Boolean> =
        when (type) {
            "overloaded_error" -> ModelErrorCode.SERVER_ERROR to true
            "api_error" -> ModelErrorCode.SERVER_ERROR to true
            "rate_limit_error" -> ModelErrorCode.RATE_LIMITED to true
            "invalid_request_error" -> ModelErrorCode.PROTOCOL to false
            "not_found_error" -> ModelErrorCode.PROTOCOL to false
            "permission_error" -> ModelErrorCode.AUTH to false
            else -> ModelErrorCode.SERVER_ERROR to false
        }

    private enum class BlockKind {
        TEXT,
        THINKING,
        TOOL,
        IGNORED,
    }

    private companion object {
        const val MAX_TOOL_CALLS = 32 // same bound as core:agent ModelTerminal.ToolCalls
        const val MAX_BLOCK_INDEX = 1_024

        val HANDLED_TYPES =
            setOf(
                "message_start",
                "content_block_start",
                "content_block_delta",
                "content_block_stop",
                "message_delta",
                "message_stop",
                "error",
            )
    }
}

/** Internal control-flow marker: a vendor stream contract violation (never escapes [AnthropicStreamDecoder]). */
private class ProtocolViolation(
    val detail: String,
) : RuntimeException(detail)
