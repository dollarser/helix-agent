package com.helix.core.agent.turn

import com.helix.core.agent.AgentTurnJournal
import com.helix.core.agent.BatchCallResolution
import com.helix.core.agent.BatchTurnRuntime
import com.helix.core.agent.ContextCompactionPlan
import com.helix.core.agent.ModelStreamState
import com.helix.core.agent.ModelStreamTerminal
import com.helix.core.agent.PromptSnapshot
import com.helix.core.agent.ResponseInputBoundary
import com.helix.core.agent.TurnMessageDraft
import com.helix.core.agent.TurnReviewCheckpoint
import com.helix.core.agent.TurnTerminalCheckpoint
import com.helix.core.model.TurnState
import kotlinx.coroutines.CancellationException

/** One live round projection. Durable receipts always precede every projection transition. */
@Suppress("TooManyFunctions") // Related checkpoints of one model/tool round share one runtime.
class TurnRoundCoordinator(
    private val sessionId: String,
    override val id: String,
    firstModelCallId: String,
    private val store: TurnRoundStore,
) : AgentTurnJournal {
    private val runtime = BatchTurnRuntime(firstModelCallId)
    private var summaryStream = false

    override fun snapshot() = runtime.snapshot()

    override fun currentStream(): ModelStreamState = runtime.currentStream()

    fun terminalCheckpoint(): TurnTerminalCheckpoint {
        val current = runtime.snapshot()
        val stream = runtime.currentStream()
        return TurnTerminalCheckpoint(
            sessionId,
            id,
            current.phase,
            current.modelCallId,
            current.modelStep,
            current.modelCallClosed,
            summaryStream,
            stream.text,
            stream.usageJson,
        )
    }

    override suspend fun retryEmptyModelStream(nextModelCallId: String) {
        val current = runtime.snapshot()
        val stream = runtime.currentStream()
        require(current.phase == TurnState.RECEIVING_MODEL && current.batchCalls.isEmpty())
        require(!current.modelCallClosed && stream.retryableError && !stream.receiving && !stream.hasToolCallFragments)
        store.retryEmptyModelStream(terminalCheckpoint(), nextModelCallId)
        runtime.closeSummary(nextModelCallId)
    }

    override suspend fun beginModelStream(compacting: Boolean): ModelStreamState {
        require(runtime.snapshot().phase == TurnState.WAITING_MODEL)
        store.beginModelStream(terminalCheckpoint())
        summaryStream = compacting
        return runtime.beginModelStream()
    }

    override suspend fun recordDiagnostic(
        kind: String,
        body: String,
    ) = store.recordDiagnostic(snapshot().modelCallId, kind, body)

    override suspend fun recordPromptSnapshot(
        prompt: PromptSnapshot?,
        compacting: Boolean,
    ) = store.recordPromptSnapshot(snapshot().modelCallId, prompt, compacting)

    override suspend fun recordRequestManifest(manifestJson: String?) =
        store.recordRequestManifest(snapshot().modelCallId, manifestJson)

    override suspend fun recordInputRequestStarted(messageIds: Set<String>) =
        store.recordInputRequestStarted(terminalCheckpoint(), messageIds)

    override suspend fun beginToolBatch(callIds: List<String>) {
        require(callIds.isNotEmpty() && callIds.all(String::isNotBlank))
        require(callIds.toSet().size == callIds.size) { "duplicate toolCallId in batch" }
        require(snapshot().phase == TurnState.RECEIVING_MODEL)
        store.beginToolBatch(terminalCheckpoint())
        runtime.beginBatch(callIds)
    }

    override suspend fun commitModelToolStep(toolCallsJson: String) {
        require(snapshot().phase == TurnState.RUNNING_TOOL)
        store.commitModelToolStep(terminalCheckpoint(), toolCallsJson)
        runtime.markModelCallClosed()
    }

    override fun settleBatchCall(
        callId: String,
        sideEffectUnknown: Boolean,
    ) = runtime.settleCall(callId, sideEffectUnknown)

    fun hasUnknownBatch(): Boolean = snapshot().batchCalls.values.any { it == BatchCallResolution.UNKNOWN }

    fun settledReviewCallIds(): List<String>? {
        val calls = snapshot().batchCalls
        if (calls.values.any { it == BatchCallResolution.PENDING }) return null
        return calls
            .filterValues { it == BatchCallResolution.UNKNOWN }
            .keys
            .toList()
            .takeIf { it.isNotEmpty() }
    }

    fun reviewCheckpoint(reviewCallIds: List<String>): TurnReviewCheckpoint {
        val current = snapshot()
        require(current.phase == TurnState.RUNNING_TOOL && current.modelCallClosed)
        require(
            current.batchCalls
                .filterValues { it == BatchCallResolution.UNKNOWN }
                .keys
                .toList() == reviewCallIds,
        )
        require(reviewCallIds.isNotEmpty())
        return TurnReviewCheckpoint(sessionId, id, current.modelStep, current.batchCalls, reviewCallIds)
    }

    fun markRuntimeParked(reviewCallIds: List<String>) = runtime.parkForReview(reviewCallIds)

    fun markTerminalCommitted(state: TurnState) = runtime.terminalize(state)

    override suspend fun openNextModelCall(
        messages: List<TurnMessageDraft>,
        nextModelCallId: String,
    ) {
        val calls = snapshot().batchCalls
        require(calls.isNotEmpty()) { "no active tool batch" }
        require(calls.values.none { it == BatchCallResolution.PENDING }) { "tool batch still has pending calls" }
        require(calls.values.none { it == BatchCallResolution.UNKNOWN }) { "tool batch has unknown side effects" }
        if (store.openNextModelCall(terminalCheckpoint(), messages, nextModelCallId) ==
            ToolResultsCommitReceipt.RECORDED_CANCELLED
        ) {
            throw CancellationException("Turn cancellation was persisted")
        }
        runtime.advanceModelCall(nextModelCallId)
    }

    fun appendSteeringBeforeRequest(draft: TurnSteeringInput): Boolean {
        require(snapshot().phase == TurnState.WAITING_MODEL)
        return store.appendSteeringBeforeRequest(terminalCheckpoint(), draft)
    }

    fun completeResponseOrContinue(
        prepared: TurnSteeringInput?,
        nextModelCallId: String,
    ): ResponseInputBoundary {
        val current = snapshot()
        require(current.phase == TurnState.RECEIVING_MODEL && current.batchCalls.isEmpty())
        require((summaryStream && current.modelCallClosed) || (!summaryStream && !current.modelCallClosed))
        val receipt = store.completeResponse(terminalCheckpoint(), prepared, nextModelCallId)
        if (receipt.boundary == ResponseInputBoundary.CONTINUED) runtime.closeSummary(nextModelCallId)
        receipt.terminal?.let { runtime.terminalize(it.state) }
        return receipt.boundary
    }

    override suspend fun commitCompaction(
        plan: ContextCompactionPlan,
        nextModelCallId: String?,
        notice: String?,
        failureReason: String?,
        saveSummary: Boolean,
    ) {
        val current = snapshot()
        val stream = currentStream()
        require(current.phase == TurnState.RECEIVING_MODEL && stream.finishedToolCalls.isEmpty())
        require(summaryStream)
        if (failureReason == null) require(stream.completed && stream.terminal(false).state == TurnState.COMPLETED)
        require(!current.modelCallClosed && current.batchCalls.isEmpty())
        require(nextModelCallId == null || nextModelCallId.isNotBlank())
        require(saveSummary || (nextModelCallId == null && failureReason == null && notice != null))
        store.commitCompaction(terminalCheckpoint(), plan, nextModelCallId, notice, failureReason, saveSummary)
        runtime.closeSummary(nextModelCallId)
    }
}
