package com.helix.core.agent

import com.helix.core.model.ModelRole
import com.helix.core.model.TurnState

/** A bounded message that becomes model-visible only after its Room row commits. */
data class TurnMessageDraft(
    val role: ModelRole,
    val kind: String,
    val content: String,
    val visualArtifact: com.helix.core.model.VisualArtifact? = null,
)

enum class ResponseInputBoundary { RECHECK, CONTINUED, TERMINAL, CANCELLED }

enum class BatchCallResolution {
    PENDING,
    SETTLED,
    UNKNOWN,
}

data class BatchTurnSnapshot(
    val phase: TurnState,
    val modelCallId: String,
    val modelStep: Int,
    val modelCallClosed: Boolean,
    val batchCalls: Map<String, BatchCallResolution>,
)

/** Immutable execution facts consumed by the Engine-owned durable terminal settlement. */
data class TurnTerminalCheckpoint(
    val sessionId: String,
    val turnId: String,
    val phase: TurnState,
    val modelCallId: String,
    val modelStep: Int,
    val modelCallClosed: Boolean,
    val summaryStream: Boolean,
    val assistantText: String,
    val usageJson: String?,
)

/** Immutable UNKNOWN-batch facts consumed by the Engine-owned review parking transaction. */
data class TurnReviewCheckpoint(
    val sessionId: String,
    val turnId: String,
    val modelStep: Int,
    val batchCalls: Map<String, BatchCallResolution>,
    val reviewCallIds: List<String>,
)

/**
 * Pure in-process checkpoint for the production batch Turn loop.
 *
 * A tool response is one batch whose calls may be concurrently active and independently settle or
 * become unknown. The durable ToolCall rows remain authoritative for per-call execution state;
 * this checkpoint owns the aggregate Turn phase and the current ModelCall/stream identity.
 */
class BatchTurnRuntime(
    firstModelCallId: String,
    initialModelStep: Int = 1,
) {
    private var phase = TurnState.WAITING_MODEL
    private var modelCallId = firstModelCallId
    private var modelStep = initialModelStep
    private var modelCallClosed = false
    private var stream = ModelStreamState()
    private var batchCalls = linkedMapOf<String, BatchCallResolution>()

    init {
        require(firstModelCallId.isNotBlank()) { "firstModelCallId must not be blank" }
        require(initialModelStep >= 1) { "initialModelStep must be >= 1" }
    }

    fun snapshot(): BatchTurnSnapshot =
        BatchTurnSnapshot(phase, modelCallId, modelStep, modelCallClosed, batchCalls.toMap())

    fun currentStream(): ModelStreamState = stream

    fun beginModelStream(): ModelStreamState {
        require(phase == TurnState.WAITING_MODEL) { "model stream requires WAITING_MODEL, was $phase" }
        phase = TurnState.RECEIVING_MODEL
        stream = ModelStreamState()
        return stream
    }

    fun beginBatch(callIds: List<String>) {
        require(phase == TurnState.RECEIVING_MODEL) { "tool batch requires RECEIVING_MODEL, was $phase" }
        require(callIds.isNotEmpty()) { "tool batch must not be empty" }
        require(callIds.all(String::isNotBlank)) { "toolCallId must not be blank" }
        require(callIds.toSet().size == callIds.size) { "duplicate toolCallId in batch" }
        phase = TurnState.RUNNING_TOOL
        batchCalls = LinkedHashMap(callIds.associateWith { BatchCallResolution.PENDING })
    }

    fun markModelCallClosed() {
        require(phase == TurnState.RUNNING_TOOL) { "model tool step closes only in RUNNING_TOOL" }
        modelCallClosed = true
    }

    fun closeSummary(nextModelCallId: String?) {
        require(phase == TurnState.RECEIVING_MODEL && batchCalls.isEmpty())
        modelCallClosed = true
        if (nextModelCallId != null) {
            phase = TurnState.WAITING_MODEL
            modelCallId = nextModelCallId
            modelStep += 1
            modelCallClosed = false
            stream = ModelStreamState()
        }
    }

    fun settleCall(
        callId: String,
        sideEffectUnknown: Boolean,
    ) {
        require(batchCalls[callId] == BatchCallResolution.PENDING) { "tool call is not pending: $callId" }
        batchCalls[callId] = if (sideEffectUnknown) BatchCallResolution.UNKNOWN else BatchCallResolution.SETTLED
    }

    fun parkForReview(reviewCallIds: List<String>) {
        require(phase == TurnState.RUNNING_TOOL) { "review park requires RUNNING_TOOL, was $phase" }
        require(modelCallClosed) { "review park requires the model call to be closed" }
        require(batchCalls.values.none { it == BatchCallResolution.PENDING }) {
            "review park requires every batch call settled"
        }
        val unknown = batchCalls.filterValues { it == BatchCallResolution.UNKNOWN }.keys.toList()
        require(unknown == reviewCallIds) { "review call identities must match the UNKNOWN batch slots" }
        phase = TurnState.NEEDS_REVIEW
    }

    fun advanceModelCall(nextModelCallId: String) {
        require(batchCalls.isNotEmpty()) { "no active tool batch" }
        require(batchCalls.values.none { it == BatchCallResolution.PENDING }) { "tool batch still has pending calls" }
        require(batchCalls.values.none { it == BatchCallResolution.UNKNOWN }) { "tool batch has unknown side effects" }
        require(nextModelCallId.isNotBlank()) { "nextModelCallId must not be blank" }
        phase = TurnState.WAITING_MODEL
        modelCallId = nextModelCallId
        modelStep += 1
        modelCallClosed = false
        stream = ModelStreamState()
        batchCalls.clear()
    }

    fun terminalize(state: TurnState) {
        require(state.isTerminal) { "terminal state required" }
        if (phase.isTerminal) return
        phase = state
    }
}
