package com.helix.core.model

/**
 * Closed failure classes of a model stream (doc 02 section 6.1 `Error(code, retryable)`).
 *
 * Mapping into the stable [ErrorCode] categories when the Agent Loop converts a stream
 * error into a [HelixError]: TRANSPORT/TIMEOUT → NETWORK, HTTP_ERROR/SERVER_ERROR →
 * EXECUTION, AUTH → PROVIDER_AUTH, RATE_LIMITED → PROVIDER_RATE_LIMIT, PROTOCOL →
 * VALIDATION, CONTENT_FILTER → POLICY.
 */
enum class ModelErrorCode {
    /** Connection-level failure (DNS/TLS/peer closed). */
    TRANSPORT,

    /** The request exceeded its execution limit before a response. */
    TIMEOUT,

    /** The provider answered with a non-2xx HTTP status. */
    HTTP_ERROR,

    /** Authentication/authorization rejected the request (401/403 family). */
    AUTH,

    /** The provider throttled the request (429 family). */
    RATE_LIMITED,

    /** The provider reported a server-side failure (5xx family). */
    SERVER_ERROR,

    /** The stream violated the protocol contract (malformed SSE/JSON, schema violation). */
    PROTOCOL,

    /** A vendor safety filter blocked the output. */
    CONTENT_FILTER,
}

/**
 * Internal unified model event (doc 02 section 6.1, doc 10 section 2.1). Adapters emit
 * these from vendor streams; the Agent Loop and core:agent consume them without ever
 * reading vendor JSON.
 *
 * Stream-level ordering rules (enforced by each adapter's fixture tests, doc 02 section
 * 6.2 and doc 10 section 2.1): deltas reference a tool call by [ModelEvent.ToolCallStarted]
 * index; [ModelEvent.ToolCallFinished] closes an index; at most one terminal of
 * [ModelEvent.Completed]/[ModelEvent.Error]/[ModelEvent.Refusal] per stream; tool
 * arguments accumulate as fragments and are parsed once at finish (doc 02 section 6.2).
 */
sealed interface ModelEvent {
    /** Incremental assistant text. Empty fragments are dropped at the adapter boundary. */
    data class TextDelta(
        val text: String,
    ) : ModelEvent {
        init {
            require(text.isNotEmpty()) { "text delta must not be empty" }
            require(text.none { it == '\u0000' }) { "text delta must not contain NUL" }
            require(text.length <= MAX_DELTA_LENGTH) { "text delta exceeds $MAX_DELTA_LENGTH chars" }
        }
    }

    /** Incremental reasoning/chain-of-thought text (only when the capability says `reasoning`). */
    data class ReasoningDelta(
        val text: String,
    ) : ModelEvent {
        init {
            require(text.isNotEmpty()) { "reasoning delta must not be empty" }
            require(text.none { it == '\u0000' }) { "reasoning delta must not contain NUL" }
            require(text.length <= MAX_DELTA_LENGTH) { "reasoning delta exceeds $MAX_DELTA_LENGTH chars" }
        }
    }

    /**
     * The model started a tool call. [index] is the call's position in this response
     * (stable across the stream); [id] is the provider-assigned call identifier —
     * [ToolCallId] so the mapping into core:agent [com.helix.core.agent] tool calls is
     * total (a vendor id outside the charset fails the stream with
     * [ModelErrorCode.PROTOCOL], never a partial event); [name] is the tool name the
     * model used (validated against the offered table by the Agent Loop, not here).
     */
    data class ToolCallStarted(
        val index: Int,
        val id: ToolCallId,
        val name: String,
    ) : ModelEvent {
        init {
            require(index >= 0) { "tool call index must be >= 0" }
            require(name.isNotBlank() && name.length <= MAX_TOOL_NAME_LENGTH) {
                "tool call name must be 1..$MAX_TOOL_NAME_LENGTH non-blank chars"
            }
            require(name.none { it.code in 0x00..0x1F || it.code in 0x7F..0x9F || it.isWhitespace() }) {
                "tool call name contains a control character"
            }
        }
    }

    /**
     * Incremental JSON fragment of a tool call's arguments (doc 02 section 6.2: incremental
     * string buffer, one JSON parse + schema validation after finish).
     */
    data class ToolArgumentsDelta(
        val index: Int,
        val jsonFragment: String,
    ) : ModelEvent {
        init {
            require(index >= 0) { "tool call index must be >= 0" }
            require(jsonFragment.isNotEmpty()) { "arguments fragment must not be empty" }
            require(jsonFragment.none { it == '\u0000' }) { "arguments fragment must not contain NUL" }
            require(jsonFragment.length <= MAX_DELTA_LENGTH) {
                "arguments fragment exceeds $MAX_DELTA_LENGTH chars"
            }
        }
    }

    /** All argument fragments for [index] were delivered; the arguments are parseable now. */
    data class ToolCallFinished(
        val index: Int,
    ) : ModelEvent {
        init {
            require(index >= 0) { "tool call index must be >= 0" }
        }
    }

    /**
     * Token accounting. Nullable fields mean "the provider did not report this figure" —
     * the Agent Loop must never treat unknown usage as 0 (doc 02 section 5.3).
     */
    data class Usage(
        val inputTokens: Long?,
        val outputTokens: Long?,
    ) : ModelEvent {
        init {
            require(inputTokens == null || inputTokens >= 0) { "inputTokens must be >= 0" }
            require(outputTokens == null || outputTokens >= 0) { "outputTokens must be >= 0" }
        }
    }

    /**
     * The model refused the request. A refusal is a legitimate completion, not an error
     * (core:agent completes the turn with finish reason "refusal"). [safeReason] is a
     * bounded, control-character-free vendor reason (may be null when the vendor gives none).
     */
    data class Refusal(
        val safeReason: String? = null,
    ) : ModelEvent {
        init {
            safeReason?.let { r ->
                require(r.isNotBlank() && r.length <= MAX_SAFE_REASON_LENGTH) {
                    "refusal reason must be 1..$MAX_SAFE_REASON_LENGTH non-blank chars"
                }
                require(r.none { it.code in 0x00..0x1F || it.code in 0x7F..0x9F }) {
                    "refusal reason contains a control character"
                }
            }
        }
    }

    /**
     * Stream failure. The stream terminates with this event (no further events follow).
     * [retryable] is the adapter's assessment; the Agent Loop decides the retry policy
     * against the turn budgets.
     */
    data class Error(
        val code: ModelErrorCode,
        val retryable: Boolean,
    ) : ModelEvent

    /**
     * The model finished normally. [finishReason] is a normalized, bounded reason
     * (e.g. "stop", "length", "tool_calls"); null means the vendor sent none — the Agent
     * Loop canonicalizes null to "stop" (HXA-011 contract).
     */
    data class Completed(
        val finishReason: String? = null,
    ) : ModelEvent {
        init {
            finishReason?.let { r ->
                require(r.isNotBlank() && r.length <= MAX_FINISH_REASON_LENGTH) {
                    "finishReason must be 1..$MAX_FINISH_REASON_LENGTH non-blank chars"
                }
                require(r.none { it.code in 0x00..0x1F || it.code in 0x7F..0x9F || it.isWhitespace() }) {
                    "finishReason contains a control character"
                }
            }
        }
    }

    companion object {
        const val MAX_DELTA_LENGTH = 65_536
        const val MAX_TOOL_NAME_LENGTH = 256
        const val MAX_SAFE_REASON_LENGTH = 1024
        const val MAX_FINISH_REASON_LENGTH = 64
    }
}
