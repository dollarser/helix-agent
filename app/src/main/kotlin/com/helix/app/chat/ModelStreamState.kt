package com.helix.app.chat

import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.TurnState

/**
 * Pure working state for one provider stream.
 *
 * Input is the provider-neutral [ModelEvent] contract. The class only accumulates bounded
 * stream facts and decides a terminal outcome; it never writes Room, publishes UI state,
 * dispatches tools, or grants authority. [ChatService] owns those effects.
 */
internal class ModelStreamState(
    private val maxToolArgumentsChars: Int = MAX_TOOL_ARGUMENTS_CHARS,
    private val maxAggregateToolArgumentsChars: Int = MAX_AGGREGATE_TOOL_ARGUMENTS_CHARS,
    private val maxTextChars: Int = MAX_MODEL_TEXT_CHARS,
    private val maxToolCalls: Int = MAX_MODEL_TOOL_CALLS,
) {
    private val textBuffer = StringBuilder()
    private val calls = LinkedHashMap<Int, MutableToolCall>()
    private var refusalLabel: String? = null
    private var errorLabel: String? = null
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
    fun apply(
        event: ModelEvent,
        errorLabelFor: (ModelErrorCode) -> String,
    ): ModelStreamUpdate {
        var update = ModelStreamUpdate.NONE
        when (event) {
            is ModelEvent.TextDelta -> {
                val startedReceiving = !receiving
                receiving = true
                if (textBuffer.length + event.text.length > maxTextChars) {
                    protocolFailure(MODEL_TEXT_OVERFLOW, MODEL_TEXT_OVERFLOW_LABEL)
                } else {
                    textBuffer.append(event.text)
                    update = ModelStreamUpdate(textChanged = true, receivingStarted = startedReceiving)
                }
            }

            is ModelEvent.Usage -> {
                usageJson = usageToJson(event)
            }

            is ModelEvent.Refusal -> {
                refusalLabel = REFUSAL_LABEL
            }

            is ModelEvent.Error -> {
                errorCode = event.code.name
                errorLabel = errorLabelFor(event.code)
            }

            is ModelEvent.ToolCallStarted -> {
                when {
                    calls.containsKey(event.index) -> {
                        protocolFailure(TOOL_STREAM_INVALID, TOOL_STREAM_INVALID_LABEL)
                    }

                    calls.size >= maxToolCalls -> {
                        protocolFailure(TOOL_CALL_COUNT_OVERFLOW, TOOL_CALL_COUNT_OVERFLOW_LABEL)
                    }

                    calls.values.any { it.callId == event.id.value } -> {
                        protocolFailure(TOOL_STREAM_INVALID, TOOL_STREAM_INVALID_LABEL)
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
                        protocolFailure(TOOL_STREAM_INVALID, TOOL_STREAM_INVALID_LABEL)
                    }

                    call.arguments.length + event.jsonFragment.length > maxToolArgumentsChars ||
                        aggregateArgumentsLength() + event.jsonFragment.length > maxAggregateToolArgumentsChars -> {
                        errorCode = TOOL_ARGUMENTS_OVERFLOW
                        errorLabel = TOOL_ARGUMENTS_OVERFLOW_LABEL
                    }

                    else -> {
                        call.arguments.append(event.jsonFragment)
                    }
                }
            }

            is ModelEvent.ToolCallFinished -> {
                val call = calls[event.index]
                if (call == null || call.finished) {
                    protocolFailure(TOOL_STREAM_INVALID, TOOL_STREAM_INVALID_LABEL)
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

    private fun protocolFailure(
        code: String,
        label: String,
    ) {
        if (errorCode == null) {
            errorCode = code
            errorLabel = label
        }
    }

    /**
     * Decides the terminal state without side effects. Precedence is intentional: user
     * cancellation, refusal, provider/protocol error, truncated tool stream, clean completion.
     */
    fun terminal(cancelled: Boolean): ModelStreamTerminal =
        when {
            cancelled -> {
                ModelStreamTerminal(TurnState.CANCELLED, null, "已停止")
            }

            refusalLabel != null -> {
                ModelStreamTerminal(TurnState.FAILED, null, refusalLabel)
            }

            errorCode != null -> {
                ModelStreamTerminal(TurnState.FAILED, errorCode, errorLabel)
            }

            calls.values.any { !it.finished } -> {
                ModelStreamTerminal(TurnState.FAILED, TOOL_STREAM_TRUNCATED, "工具调用流不完整，请重试")
            }

            else -> {
                ModelStreamTerminal(TurnState.COMPLETED, null, null)
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

    private companion object {
        const val REFUSAL_LABEL = "模型拒绝（安全/策略）"
        const val TOOL_ARGUMENTS_OVERFLOW = "TOOL_ARGS_OVERFLOW"
        const val TOOL_ARGUMENTS_OVERFLOW_LABEL = "工具参数超出上限"
        const val TOOL_CALL_COUNT_OVERFLOW = "TOOL_CALL_COUNT_OVERFLOW"
        const val TOOL_CALL_COUNT_OVERFLOW_LABEL = "工具调用数量超出上限"
        const val TOOL_STREAM_INVALID = "TOOL_STREAM_INVALID"
        const val TOOL_STREAM_INVALID_LABEL = "工具调用流协议无效，请重试"
        const val TOOL_STREAM_TRUNCATED = "TOOL_STREAM_TRUNCATED"
        const val MODEL_TEXT_OVERFLOW = "MODEL_TEXT_OVERFLOW"
        const val MODEL_TEXT_OVERFLOW_LABEL = "模型输出超出上限"
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

/** A stream terminal decision; persistence and display remain application effects. */
internal data class ModelStreamTerminal(
    val state: TurnState,
    val errorCode: String?,
    val displayLabel: String?,
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
