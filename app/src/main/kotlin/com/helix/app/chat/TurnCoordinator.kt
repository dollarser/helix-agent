package com.helix.app.chat

import com.helix.core.model.Clock
import com.helix.core.model.ModelRole
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.repository.MessageAttachmentRepository

/** A bounded message that becomes model-visible only after its Room row commits. */
internal data class TurnMessageDraft(
    val role: ModelRole,
    val kind: String,
    val content: String,
)

internal data class TurnStartSpec(
    val sessionId: String,
    val turnId: String,
    val firstModelCallId: String,
    val providerSnapshot: String,
    val userText: String?,
    val attachments: List<MessageAttachmentRepository.Binding> = emptyList(),
)

internal enum class BatchCallResolution {
    PENDING,
    SETTLED,
    UNKNOWN,
}

internal data class BatchTurnSnapshot(
    val phase: TurnState,
    val modelCallId: String,
    val modelStep: Int,
    val modelCallClosed: Boolean,
    val batchCalls: Map<String, BatchCallResolution>,
)

/**
 * Pure in-process checkpoint for the production batch Turn loop.
 *
 * It deliberately does not reuse the M1 serial [com.helix.core.agent.TurnReducer]: a tool
 * response is one batch whose calls may be concurrently active and independently settle or
 * become unknown. The durable ToolCall rows remain authoritative for per-call execution state;
 * this checkpoint owns the aggregate Turn phase and the current ModelCall/stream identity.
 */
internal class BatchTurnRuntime(
    firstModelCallId: String,
) {
    private var phase = TurnState.WAITING_MODEL
    private var modelCallId = firstModelCallId
    private var modelStep = 1
    private var modelCallClosed = false
    private var stream = ModelStreamState()
    private var batchCalls = linkedMapOf<String, BatchCallResolution>()

    init {
        require(firstModelCallId.isNotBlank()) { "firstModelCallId must not be blank" }
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

    fun settleCall(
        callId: String,
        sideEffectUnknown: Boolean,
    ) {
        require(batchCalls[callId] == BatchCallResolution.PENDING) { "tool call is not pending: $callId" }
        batchCalls[callId] = if (sideEffectUnknown) BatchCallResolution.UNKNOWN else BatchCallResolution.SETTLED
    }

    fun requireBatchSettled() {
        require(batchCalls.isNotEmpty()) { "no active tool batch" }
        require(batchCalls.values.none { it == BatchCallResolution.PENDING }) { "tool batch still has pending calls" }
        require(batchCalls.values.none { it == BatchCallResolution.UNKNOWN }) { "tool batch has unknown side effects" }
    }

    fun advanceModelCall(nextModelCallId: String) {
        requireBatchSettled()
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
        phase = state
    }
}

/**
 * The single production owner of Turn/ModelCall lifecycle persistence for chat execution.
 * External tool effects remain outside Room transactions; their durable per-call settlements
 * are completed first, then model-visible backfill and the next ModelCall commit atomically.
 */
internal class TurnCoordinator private constructor(
    private val storage: HelixStorage,
    private val clock: Clock,
    private val idGenerator: () -> String,
    private val sessionId: String,
    private val turnId: String,
    private val providerSnapshot: String,
    private val runtime: BatchTurnRuntime,
) {
    val id: String
        get() = turnId

    fun snapshot(): BatchTurnSnapshot = runtime.snapshot()

    fun currentStream(): ModelStreamState = runtime.currentStream()

    fun beginModelStream(): ModelStreamState {
        val current = runtime.snapshot()
        require(current.phase == TurnState.WAITING_MODEL)
        transitionPersisted(TurnState.RECEIVING_MODEL, current.modelStep)
        return runtime.beginModelStream()
    }

    fun beginToolBatch(callIds: List<String>) {
        // Validate the full batch before changing durable state.
        require(callIds.isNotEmpty())
        require(callIds.all(String::isNotBlank)) { "toolCallId must not be blank" }
        require(callIds.toSet().size == callIds.size) { "duplicate toolCallId in batch" }
        val current = runtime.snapshot()
        require(current.phase == TurnState.RECEIVING_MODEL)
        transitionPersisted(TurnState.RUNNING_TOOL, current.modelStep)
        runtime.beginBatch(callIds)
    }

    /** Atomically closes the model call and commits the assistant tool-call message. */
    fun commitModelToolStep(toolCallsJson: String) {
        val current = runtime.snapshot()
        require(current.phase == TurnState.RUNNING_TOOL)
        storage.withTransaction {
            storage.modelCalls.update(
                storage.modelCalls.resolve(current.modelCallId),
                CALL_COMPLETED,
                runtime.currentStream().usageJson,
                null,
            )
            storage.messages.append(
                idGenerator(),
                sessionId,
                turnId,
                ModelRole.ASSISTANT.name,
                ChatHistoryBuilder.KIND_TOOL_CALLS,
                toolCallsJson,
            )
        }
        runtime.markModelCallClosed()
    }

    fun settleBatchCall(
        callId: String,
        sideEffectUnknown: Boolean,
    ) {
        runtime.settleCall(callId, sideEffectUnknown)
    }

    /** Atomically records ordered model-visible results and opens the next model step. */
    fun openNextModelCall(
        messages: List<TurnMessageDraft>,
        nextModelCallId: String,
    ) {
        runtime.requireBatchSettled()
        val current = runtime.snapshot()
        storage.withTransaction {
            var turn = storage.turns.resolve(turnId)
            turn = storage.turns.updateState(turn, TurnState.RECORDING_TOOL_RESULT, current.modelStep, null, null)
            messages.forEach { message ->
                storage.messages.append(
                    idGenerator(),
                    sessionId,
                    turnId,
                    message.role.name,
                    message.kind,
                    message.content,
                )
            }
            turn = storage.turns.updateState(turn, TurnState.BUILDING_CONTEXT, current.modelStep, null, null)
            storage.modelCalls.append(nextModelCallId, turnId, providerSnapshot, CALL_RUNNING)
            storage.turns.updateState(turn, TurnState.WAITING_MODEL, current.modelStep, null, null)
        }
        runtime.advanceModelCall(nextModelCallId)
    }

    /** Atomically commits assistant text, Turn terminal, and the still-open ModelCall terminal. */
    fun terminalize(outcome: ModelStreamTerminal) {
        val current = runtime.snapshot()
        val stream = runtime.currentStream()
        val endedAt = clock.now().toEpochMilli()
        storage.withTransaction {
            if (!current.modelCallClosed && stream.text.isNotBlank()) {
                storage.messages.append(
                    idGenerator(),
                    sessionId,
                    turnId,
                    ModelRole.ASSISTANT.name,
                    ChatHistoryBuilder.KIND_TEXT,
                    stream.text,
                )
            }
            var turn = storage.turns.resolve(turnId)
            if (outcome.state == TurnState.CANCELLED && turn.state != TurnState.CANCELLING.name) {
                turn = storage.turns.updateState(turn, TurnState.CANCELLING, current.modelStep, null, null)
            }
            storage.turns.updateState(turn, outcome.state, current.modelStep, endedAt, outcome.errorCode)
            if (!current.modelCallClosed) {
                storage.modelCalls.update(
                    storage.modelCalls.resolve(current.modelCallId),
                    callState(outcome.state),
                    stream.usageJson,
                    null,
                )
            }
        }
        runtime.terminalize(outcome.state)
    }

    private fun transitionPersisted(
        state: TurnState,
        step: Int,
    ) {
        storage.turns.updateState(storage.turns.resolve(turnId), state, step, null, null)
    }

    companion object {
        private const val CALL_RUNNING = "RUNNING"
        private const val CALL_COMPLETED = "COMPLETED"
        private const val CALL_CANCELLED = "CANCELLED"
        private const val CALL_FAILED = "FAILED"

        fun start(
            storage: HelixStorage,
            clock: Clock,
            idGenerator: () -> String,
            spec: TurnStartSpec,
        ): TurnCoordinator {
            val now = clock.now().toEpochMilli()
            storage.withTransaction {
                var turn = storage.turns.start(spec.turnId, spec.sessionId, now)
                turn = storage.turns.updateState(turn, TurnState.BUILDING_CONTEXT, 0, null, null)
                if (spec.userText != null || spec.attachments.isNotEmpty()) {
                    val message =
                        storage.messages.append(
                            idGenerator(),
                            spec.sessionId,
                            spec.turnId,
                            ModelRole.USER.name,
                            ChatHistoryBuilder.KIND_TEXT,
                            spec.userText.orEmpty(),
                        )
                    // An attachment-only send still needs a message row to own the bindings; the
                    // bind pairs with the insert in this transaction (ADR-0014).
                    if (spec.attachments.isNotEmpty()) {
                        storage.messageAttachments.bind(message.id, spec.attachments)
                    }
                }
                storage.modelCalls.append(
                    spec.firstModelCallId,
                    spec.turnId,
                    spec.providerSnapshot,
                    CALL_RUNNING,
                )
                storage.turns.updateState(turn, TurnState.WAITING_MODEL, 0, null, null)
            }
            return TurnCoordinator(
                storage,
                clock,
                idGenerator,
                spec.sessionId,
                spec.turnId,
                spec.providerSnapshot,
                BatchTurnRuntime(spec.firstModelCallId),
            )
        }

        private fun callState(turn: TurnState): String =
            when (turn) {
                TurnState.COMPLETED -> CALL_COMPLETED
                TurnState.CANCELLED -> CALL_CANCELLED
                else -> CALL_FAILED
            }
    }
}
