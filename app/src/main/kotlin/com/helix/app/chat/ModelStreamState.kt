package com.helix.app.chat

import com.helix.core.model.ModelEvent
import com.helix.core.model.TurnState

/**
 * Pure working state for one provider stream.
 *
 * Input is the provider-neutral [ModelEvent] contract. The class only accumulates bounded
 * stream facts and decides a terminal outcome as a STABLE code — it never produces a
 * user-visible string (HXA-069: the label is resolved to the current locale by the
 * Android-side caller, [ChatService]). It never writes Room, publishes UI state, dispatches
 * tools, or grants authority.
 */
internal class ModelStreamState(
    private val maxToolArgumentsChars: Int = MAX_TOOL_ARGUMENTS_CHARS,
    private val maxAggregateToolArgumentsChars: Int = MAX_AGGREGATE_TOOL_ARGUMENTS_CHARS,
    private val maxTextChars: Int = MAX_MODEL_TEXT_CHARS,
    private val maxToolCalls: Int = MAX_MODEL_TOOL_CALLS,
) {
    private val textBuffer = StringBuilder()
    private val calls = LinkedHashMap<Int, MutableToolCall>()
    private var refused = false
    private var errorCode: String? = null

    var usageJson: String? = null
        private set

    var receiving: Boolean = false
        private set

    val text: String
        get() = textBuffer.toString()

    val finishedToolCalls: List<BufferedModelToolCall>
        get() =
            calls.entries
                .asSequence()
                .filter { it.value.finished }
                .sortedBy { it.key }
                .map { it.value.snapshot() }
                .toList()

    init {
        require(maxToolArgumentsChars > 0) { "maxToolArgumentsChars must be > 0" }
        require(maxAggregateToolArgumentsChars >= maxToolArgumentsChars) {
            "maxAggregateToolArgumentsChars must be >= maxToolArgumentsChars"
        }
        require(maxTextChars > 0) { "maxTextChars must be > 0" }
        require(maxToolCalls > 0) { "maxToolCalls must be > 0" }
    }

    /**
     * Applies exactly one event. The returned flags are the only UI/persistence effects the
     * caller needs to perform for an incremental event.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod") // exhaustive closed ModelEvent protocol gate
    fun apply(event: ModelEvent): ModelStreamUpdate {
        var update = ModelStreamUpdate.NONE
        when (event) {
            is ModelEvent.TextDelta -> {
                val startedReceiving = !receiving
                receiving = true
                if (textBuffer.length + event.text.length > maxTextChars) {
                    protocolFailure(MODEL_TEXT_OVERFLOW)
                } else {
                    textBuffer.append(event.text)
                    update = ModelStreamUpdate(textChanged = true, receivingStarted = startedReceiving)
                }
            }

            is ModelEvent.Usage -> {
                usageJson = usageToJson(event)
            }

            is ModelEvent.Refusal -> {
                refused = true
            }

            is ModelEvent.Error -> {
                errorCode = event.code.name
            }

            is ModelEvent.ToolCallStarted -> {
                when {
                    calls.containsKey(event.index) -> {
                        protocolFailure(TOOL_STREAM_INVALID)
                    }

                    calls.size >= maxToolCalls -> {
                        protocolFailure(TOOL_CALL_COUNT_OVERFLOW)
                    }

                    calls.values.any { it.callId == event.id.value } -> {
                        protocolFailure(TOOL_STREAM_INVALID)
                    }

                    else -> {
                        calls[event.index] = MutableToolCall(event.id.value, event.name)
                    }
                }
            }

            is ModelEvent.ToolArgumentsDelta -> {
                val call = calls[event.index]
                when {
                    call == null || call.finished -> {
                        protocolFailure(TOOL_STREAM_INVALID)
                    }

                    call.arguments.length + event.jsonFragment.length > maxToolArgumentsChars ||
                        aggregateArgumentsLength() + event.jsonFragment.length > maxAggregateToolArgumentsChars -> {
                        errorCode = TOOL_ARGUMENTS_OVERFLOW
                    }

                    else -> {
                        call.arguments.append(event.jsonFragment)
                    }
                }
            }

            is ModelEvent.ToolCallFinished -> {
                val call = calls[event.index]
                if (call == null || call.finished) {
                    protocolFailure(TOOL_STREAM_INVALID)
                } else {
                    call.finished = true
                }
            }

            is ModelEvent.ReasoningDelta,
            is ModelEvent.Completed,
            -> {
                Unit
            }
        }
        return update
    }

    private fun aggregateArgumentsLength(): Int = calls.values.sumOf { it.arguments.length }

    private fun protocolFailure(code: String) {
        if (errorCode == null) {
            errorCode = code
        }
    }

    /**
     * Decides the terminal state without side effects. Precedence is intentional: user
     * cancellation, refusal, provider/protocol error, truncated tool stream, clean completion.
     * [ModelStreamTerminal.errorCode] is a STABLE code (a provider `ModelErrorCode` name, or one
     * of this class's codes); the Android-side caller resolves it to a localized label.
     */
    fun terminal(cancelled: Boolean): ModelStreamTerminal =
        when {
            cancelled -> {
                ModelStreamTerminal(TurnState.CANCELLED, null)
            }

            refused -> {
                ModelStreamTerminal(TurnState.FAILED, REFUSAL)
            }

            errorCode != null -> {
                ModelStreamTerminal(TurnState.FAILED, errorCode)
            }

            calls.values.any { !it.finished } -> {
                ModelStreamTerminal(TurnState.FAILED, TOOL_STREAM_TRUNCATED)
            }

            else -> {
                ModelStreamTerminal(TurnState.COMPLETED, null)
            }
        }

    private class MutableToolCall(
        val callId: String,
        val name: String,
    ) {
        val arguments = StringBuilder()
        var finished = false

        fun snapshot() = BufferedModelToolCall(callId, name, arguments.toString())
    }

    companion object {
        const val REFUSAL = "REFUSAL"
        const val TOOL_ARGUMENTS_OVERFLOW = "TOOL_ARGS_OVERFLOW"
        const val TOOL_CALL_COUNT_OVERFLOW = "TOOL_CALL_COUNT_OVERFLOW"
        const val TOOL_STREAM_INVALID = "TOOL_STREAM_INVALID"
        const val TOOL_STREAM_TRUNCATED = "TOOL_STREAM_TRUNCATED"
        const val MODEL_TEXT_OVERFLOW = "MODEL_TEXT_OVERFLOW"
    }
}

/** The effects of applying one incremental event; terminalization is decided separately. */
internal data class ModelStreamUpdate(
    val textChanged: Boolean,
    val receivingStarted: Boolean,
) {
    companion object {
        val NONE = ModelStreamUpdate(textChanged = false, receivingStarted = false)
    }
}

/** Immutable tool-call snapshot exposed only after the provider closed the call. */
internal data class BufferedModelToolCall(
    val callId: String,
    val name: String,
    val arguments: String,
)

/**
 * A stream terminal decision; the caller resolves [errorCode] to a localized label and owns
 * persistence and display (application effects).
 */
internal data class ModelStreamTerminal(
    val state: TurnState,
    val errorCode: String?,
)

/** Per-call total accumulated argument budget in UTF-16 code units (bounded working memory). */
internal const val MAX_TOOL_ARGUMENTS_CHARS = 1_048_576
internal const val MAX_AGGREGATE_TOOL_ARGUMENTS_CHARS = 2_097_152
internal const val MAX_MODEL_TEXT_CHARS = 262_144
internal const val MAX_MODEL_TOOL_CALLS = 32

/** Encodes nullable token usage without turning an unreported value into zero. */
private fun usageToJson(usage: ModelEvent.Usage): String? {
    val input = usage.inputTokens
    val output = usage.outputTokens
    if (input == null && output == null) return null
    return buildString {
        append("{")
        if (input != null) append("\"inputTokens\":").append(input)
        if (input != null && output != null) append(",")
        if (output != null) append("\"outputTokens\":").append(output)
        append("}")
    }
}
