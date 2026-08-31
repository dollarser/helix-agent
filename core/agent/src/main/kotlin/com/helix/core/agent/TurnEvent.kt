package com.helix.core.agent

import com.helix.core.model.ApprovalId
import com.helix.core.model.ArtifactRef
import com.helix.core.model.HelixError
import com.helix.core.model.ModelCallId
import com.helix.core.model.ToolCallId
import com.helix.core.model.ToolName
import com.helix.core.model.ToolVersion

/**
 * Events applied to a [TurnState] by the [TurnReducer]. The event is what the turn
 * coordinator observed (context built, provider stream event, tool execution result, user
 * action, process death); it carries only identifiers and bounded metadata, never full
 * message or tool output bodies (those are persisted by the coordinator and referenced by
 * [ArtifactRef]).
 */
sealed interface TurnEvent {
    sealed interface Lifecycle : TurnEvent {
        /** User submitted a message; a new turn is created. Valid from the CREATED phase. */
        data object TurnSubmitted : Lifecycle

        /**
         * Context build finished; the coordinator commits the next model call. Carries the
         * request size in bytes used for the conservative token estimate. Valid from the
         * BUILDING_CONTEXT phase.
         */
        data class ContextReady(
            val callId: ModelCallId,
            val requestBytes: Long,
        ) : Lifecycle {
            init {
                require(requestBytes >= 0) { "requestBytes must be >= 0" }
            }
        }

        /**
         * User requested cancellation. Valid from any non-terminal phase except CANCELLING
         * and INTERRUPTED (matching the core:model TurnState rules).
         */
        data object CancelRequested : Lifecycle

        /**
         * The coordinator finished cancelling the in-flight work. `uncertainToolCallId` is
         * the call whose external effect is actually unknown (null when cancellation
         * completed cleanly). Valid from CANCELLING.
         */
        data class CancelFinished(
            val uncertainToolCallId: ToolCallId?,
        ) : Lifecycle

        /**
         * A human (recovery flow, HXA-015) resolved the uncertain tool call left by process
         * death. Applies the given outcome to the tracked call. Valid from INTERRUPTED while
         * an uncertain call is tracked.
         */
        data class UncertainToolCallResolved(
            val outcome: ToolOutcome,
        ) : Lifecycle

        /**
         * Explicit user resume of an interrupted turn. Valid from INTERRUPTED only after the
         * uncertain call (if any) was resolved.
         */
        data object TurnResumed : Lifecycle

        /** User discarded the turn. Valid from INTERRUPTED and CANCELLING. */
        data object TurnDiscarded : Lifecycle

        /**
         * The process died (crash, kill, power loss). Equivalent to [TurnReducer.afterProcessDeath].
         */
        data object ProcessDied : Lifecycle
    }

    sealed interface Model : TurnEvent {
        /** The provider stream for the committed call started. Valid from WAITING_MODEL. */
        data class StreamStarted(
            val callId: ModelCallId,
        ) : Model

        /**
         * Provider reported usage for the active call (mid-stream or final). Null fields stay
         * estimated. Valid from RECEIVING_MODEL.
         */
        data class UsageReported(
            val callId: ModelCallId,
            val usage: TokenUsage,
            val responseBytes: Long,
        ) : Model {
            init {
                require(responseBytes >= 0) { "responseBytes must be >= 0" }
            }
        }

        /**
         * The model call finished with a terminal payload. Valid from RECEIVING_MODEL.
         */
        data class Finished(
            val callId: ModelCallId,
            val responseBytes: Long,
            val terminal: ModelTerminal,
        ) : Model {
            init {
                require(responseBytes >= 0) { "responseBytes must be >= 0" }
            }
        }

        /**
         * The model call failed before a terminal payload (transport error, auth error,
         * dropped stream). Valid from WAITING_MODEL and RECEIVING_MODEL.
         */
        data class CallFailed(
            val callId: ModelCallId,
            val responseBytes: Long,
            val error: HelixError,
        ) : Model {
            init {
                require(responseBytes >= 0) { "responseBytes must be >= 0" }
            }
        }
    }

    sealed interface Tool : TurnEvent {
        /** User approved the pending call; execution starts. Valid from WAITING_APPROVAL. */
        data class CallApproved(
            val toolCallId: ToolCallId,
            val approvalId: ApprovalId,
        ) : Tool

        /**
         * User (or policy) denied the pending call. The denial is a legitimate tool result:
         * it is recorded and the turn re-enters the context/model loop so the agent can
         * adjust. Valid from WAITING_APPROVAL.
         */
        data class CallDenied(
            val toolCallId: ToolCallId,
            val reason: String,
        ) : Tool {
            init {
                requireBoundedText("reason", reason)
            }
        }

        /**
         * The tool execution finished (success, failure or timeout). Valid from RUNNING_TOOL
         * for the active call.
         */
        data class ExecutionFinished(
            val toolCallId: ToolCallId,
            val outcome: ToolOutcome,
        ) : Tool

        /**
         * The coordinator persisted the recorded tool result. Advances the serial tool queue:
         * either the next call of the same model response or the context/model loop. Valid
         * from RECORDING_TOOL_RESULT.
         */
        data object ResultsRecorded : Tool
    }
}

internal const val MAX_BOUNDED_TEXT_LENGTH = 512

internal fun requireBoundedText(
    name: String,
    value: String,
) {
    require(value.isNotBlank()) { "$name must not be blank" }
    require(value.length <= MAX_BOUNDED_TEXT_LENGTH) { "$name must be <= $MAX_BOUNDED_TEXT_LENGTH chars" }
}

/**
 * Terminal payload of a model call. The reducer only needs the shape and the tool call
 * metadata; full text bodies stay with the coordinator/persistence layer.
 */
sealed interface ModelTerminal {
    /** The model finished with a final answer. */
    data class FinalText(
        val finishReason: String?,
    ) : ModelTerminal

    /**
     * The model refused the request. A refusal is a legitimate completion (the model safely
     * stopped), not an error: the turn completes with finish reason "refusal".
     */
    data class Refusal(
        val finishReason: String? = "refusal",
    ) : ModelTerminal

    /**
     * The model requested tool calls. First version executes all calls of one response
     * serially (doc 02 section 5.3); provider protocols require every call to receive a
     * result before the next model call.
     */
    data class ToolCalls(
        val calls: List<ModelToolCall>,
    ) : ModelTerminal {
        init {
            require(calls.isNotEmpty()) { "a tool-call response must contain at least one call" }
            require(calls.size <= MAX_CALLS) { "at most $MAX_CALLS tool calls per response" }
            val ids = calls.map { it.toolCallId.value }
            require(ids.toSet().size == ids.size) { "duplicate toolCallId in one response" }
        }

        private companion object {
            const val MAX_CALLS = 32
        }
    }

    /** Protocol-level failure of the provider stream (malformed SSE, schema violation, ...). */
    data class ProtocolError(
        val error: HelixError,
    ) : ModelTerminal
}

/** A tool call requested by the model, with the approval decision precomputed by policy. */
data class ModelToolCall(
    val toolCallId: ToolCallId,
    val toolName: ToolName,
    val toolVersion: ToolVersion,
    val requiresApproval: Boolean,
)

/**
 * Outcome of a tool call as recorded by the turn. Denial is a first-class outcome (a
 * legitimate tool result), distinct from failure.
 */
sealed interface ToolOutcome {
    data class Succeeded(
        val outputRef: ArtifactRef?,
        val summary: String,
        val verified: Boolean,
    ) : ToolOutcome {
        init {
            requireBoundedText("summary", summary)
        }
    }

    data class Failed(
        val error: HelixError,
    ) : ToolOutcome

    data object TimedOut : ToolOutcome

    data class Denied(
        val reason: String,
    ) : ToolOutcome {
        init {
            requireBoundedText("reason", reason)
        }
    }

    data object Cancelled : ToolOutcome
}
