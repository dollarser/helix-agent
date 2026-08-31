package com.helix.core.agent

import com.helix.core.model.ErrorCode
import com.helix.core.model.HelixError
import com.helix.core.model.ToolCallState
import com.helix.core.model.TurnState as TurnPhase

/**
 * Result of one [TurnReducer.reduce] step.
 *
 * `ignored = true` means the event is not applicable in the current phase (e.g. a stale
 * provider event after the turn already failed): the state is returned unchanged and no
 * effects are produced. The coordinator logs and drops ignored events.
 */
data class TurnStep(
    val state: TurnState,
    val effects: List<TurnEffect> = emptyList(),
    val ignored: Boolean = false,
) {
    companion object {
        fun unchanged(state: TurnState): TurnStep = TurnStep(state, emptyList(), true)
    }
}

// A turn reducer keeps one function per event transition; the function count is intrinsic
// to the state machine, not a god-class smell, so the threshold is suppressed here only.
@Suppress("TooManyFunctions")
object TurnReducer {
    /** Applies one event to the turn state. Pure: no I/O, no clock, no randomness. */
    fun reduce(
        state: TurnState,
        event: TurnEvent,
    ): TurnStep =
        when (event) {
            is TurnEvent.Lifecycle -> reduceLifecycle(state, event)
            is TurnEvent.Model -> reduceModel(state, event)
            is TurnEvent.Tool -> reduceTool(state, event)
        }

    /**
     * Maps a turn across process death (crash, kill, power loss): any non-terminal phase
     * except INTERRUPTED becomes INTERRUPTED. A call that was actually running (RUNNING_TOOL,
     * or the candidate tracked while CANCELLING) is marked as an uncertain side effect; a
     * call still waiting for approval was never executed and is not uncertain.
     */
    fun afterProcessDeath(state: TurnState): TurnState {
        if (!state.phase.canBecomeInterruptedOnProcessDeath()) return state
        val phaseBefore = state.phase
        val uncertain =
            if (phaseBefore == TurnPhase.RUNNING_TOOL && state.pendingCalls.isNotEmpty()) {
                state.pendingCalls.first().toolCallId
            } else if (phaseBefore == TurnPhase.CANCELLING) {
                state.uncertainToolCallId
            } else {
                null
            }
        return state.copy(phase = TurnPhase.INTERRUPTED, uncertainToolCallId = uncertain)
    }

    private fun reduceLifecycle(
        state: TurnState,
        event: TurnEvent.Lifecycle,
    ): TurnStep =
        when (event) {
            is TurnEvent.Lifecycle.TurnSubmitted -> onSubmitted(state)
            is TurnEvent.Lifecycle.ContextReady -> onContextReady(state, event)
            is TurnEvent.Lifecycle.CancelRequested -> onCancelRequested(state)
            is TurnEvent.Lifecycle.CancelFinished -> onCancelFinished(state, event)
            is TurnEvent.Lifecycle.UncertainToolCallResolved -> onUncertainResolved(state, event)
            is TurnEvent.Lifecycle.TurnResumed -> onResumed(state)
            is TurnEvent.Lifecycle.TurnDiscarded -> onDiscarded(state)
            is TurnEvent.Lifecycle.ProcessDied -> TurnStep(afterProcessDeath(state))
        }

    private fun onSubmitted(state: TurnState): TurnStep {
        if (state.phase != TurnPhase.CREATED) return TurnStep.unchanged(state)
        val next = state.copy(phase = TurnPhase.BUILDING_CONTEXT)
        return step(state, next, listOf(TurnEffect.BuildContext))
    }

    private fun onContextReady(
        state: TurnState,
        event: TurnEvent.Lifecycle.ContextReady,
    ): TurnStep {
        if (state.phase != TurnPhase.BUILDING_CONTEXT) return TurnStep.unchanged(state)
        val estimate = TokenEstimator.estimateTokens(event.requestBytes)
        val budgets = state.budgets
        val exhaustion =
            when {
                state.step + 1 > budgets.maxSteps -> {
                    budgetError(state, "maxSteps", "Turn budget exceeded: maximum steps reached.")
                }

                state.modelCalls + 1 > budgets.maxModelCalls -> {
                    budgetError(
                        state,
                        "maxModelCalls",
                        "Turn budget exceeded: maximum model calls reached.",
                    )
                }

                estimate > budgets.maxInputTokens -> {
                    budgetError(
                        state,
                        "maxInputTokens",
                        "Model request exceeds the per-call input token budget.",
                    )
                }

                state.usedTotalTokens + estimate > budgets.maxTotalTokens -> {
                    budgetError(
                        state,
                        "maxTotalTokens",
                        "Turn budget exceeded: cumulative token budget reached.",
                    )
                }

                else -> {
                    null
                }
            }
        return if (exhaustion != null) {
            val next = state.copy(phase = TurnPhase.FAILED, error = exhaustion)
            step(state, next, listOf(TurnEffect.FailTurn(exhaustion)))
        } else {
            val account = CallTokenAccount(callId = event.callId, requestBytes = event.requestBytes)
            val next =
                state.copy(
                    phase = TurnPhase.WAITING_MODEL,
                    step = state.step + 1,
                    modelCalls = state.modelCalls + 1,
                    committedCallId = event.callId,
                    callAccounts = state.callAccounts + account,
                )
            val outputCap = minOf(budgets.maxOutputTokens, budgets.maxTotalTokens - next.usedTotalTokens)
            step(state, next, listOf(TurnEffect.StartModelCall(event.callId, next.step, outputCap)))
        }
    }

    private fun onCancelRequested(state: TurnState): TurnStep {
        val cancellable =
            !state.phase.isTerminal &&
                state.phase != TurnPhase.CANCELLING &&
                state.phase != TurnPhase.INTERRUPTED
        if (!cancellable) return TurnStep.unchanged(state)
        val candidate =
            if (state.phase == TurnPhase.RUNNING_TOOL && state.pendingCalls.isNotEmpty()) {
                state.pendingCalls.first().toolCallId
            } else {
                null
            }
        // The in-flight model stream / tool call is being torn down; any late event for it
        // is stale and will be ignored by the id checks.
        val next =
            state.copy(
                phase = TurnPhase.CANCELLING,
                uncertainToolCallId = candidate,
                committedCallId = null,
                activeCallId = null,
            )
        return step(state, next, listOf(TurnEffect.CancelInFlight))
    }

    private fun onCancelFinished(
        state: TurnState,
        event: TurnEvent.Lifecycle.CancelFinished,
    ): TurnStep {
        if (state.phase != TurnPhase.CANCELLING) return TurnStep.unchanged(state)
        val next =
            state.copy(
                phase = TurnPhase.CANCELLED,
                finishReason = "cancelled",
                uncertainToolCallId = event.uncertainToolCallId,
                pendingCalls = emptyList(),
            )
        return step(state, next, listOf(TurnEffect.CompleteTurn("cancelled")))
    }

    private fun onUncertainResolved(
        state: TurnState,
        event: TurnEvent.Lifecycle.UncertainToolCallResolved,
    ): TurnStep {
        val uncertain = state.uncertainToolCallId
        if (state.phase != TurnPhase.INTERRUPTED || uncertain == null) return TurnStep.unchanged(state)
        val next =
            state.copy(
                uncertainToolCallId = null,
                recordedOutcomes = state.recordedOutcomes + RecordedToolOutcome(uncertain, event.outcome),
            )
        return step(state, next)
    }

    /**
     * Explicit user resume. Calls of the interrupted model response that were never executed
     * are recorded as failed with [ErrorCode.INTERRUPTED] (never re-executed): provider
     * conversations require a result for every tool call, and recovery must not replay calls
     * whose side effects are unclear (HXA-015 owns that review flow).
     */
    private fun onResumed(state: TurnState): TurnStep {
        if (state.phase != TurnPhase.INTERRUPTED || state.uncertainToolCallId != null) {
            return TurnStep.unchanged(state)
        }
        val recordedIds = state.recordedOutcomes.mapTo(mutableSetOf()) { it.toolCallId }
        val unexecuted =
            state.pendingCalls
                .filter { it.toolCallId !in recordedIds }
                .map { RecordedToolOutcome(it.toolCallId, ToolOutcome.Failed(interruptError(state))) }
        val next =
            state.copy(
                phase = TurnPhase.BUILDING_CONTEXT,
                pendingCalls = emptyList(),
                recordedOutcomes = state.recordedOutcomes + unexecuted,
            )
        return step(state, next, listOf(TurnEffect.BuildContext))
    }

    private fun onDiscarded(state: TurnState): TurnStep {
        if (state.phase != TurnPhase.INTERRUPTED && state.phase != TurnPhase.CANCELLING) {
            return TurnStep.unchanged(state)
        }
        val next =
            state.copy(
                phase = TurnPhase.CANCELLED,
                finishReason = "discarded",
                pendingCalls = emptyList(),
            )
        return step(state, next, listOf(TurnEffect.CompleteTurn("discarded")))
    }

    private fun interruptError(state: TurnState): HelixError =
        HelixError(
            code = ErrorCode.INTERRUPTED,
            userMessage = "The tool call was not executed because the turn was interrupted.",
            retryable = false,
            correlationId = state.correlationId,
        )

    private fun reduceModel(
        state: TurnState,
        event: TurnEvent.Model,
    ): TurnStep =
        when (event) {
            is TurnEvent.Model.StreamStarted -> onStreamStarted(state, event)
            is TurnEvent.Model.UsageReported -> onUsageReported(state, event)
            is TurnEvent.Model.Finished -> onModelFinished(state, event)
            is TurnEvent.Model.CallFailed -> onCallFailed(state, event)
        }

    private fun onStreamStarted(
        state: TurnState,
        event: TurnEvent.Model.StreamStarted,
    ): TurnStep {
        if (state.phase != TurnPhase.WAITING_MODEL || state.committedCallId != event.callId) {
            return TurnStep.unchanged(state)
        }
        val next =
            state.copy(
                phase = TurnPhase.RECEIVING_MODEL,
                activeCallId = event.callId,
                committedCallId = null,
            )
        return step(state, next)
    }

    private fun onUsageReported(
        state: TurnState,
        event: TurnEvent.Model.UsageReported,
    ): TurnStep {
        if (state.phase != TurnPhase.RECEIVING_MODEL || state.activeCallId != event.callId) {
            return TurnStep.unchanged(state)
        }
        val accounts =
            state.callAccounts.map { account ->
                if (account.callId != event.callId) {
                    account
                } else {
                    account.copy(
                        inputTokens = event.usage.inputTokens ?: account.inputTokens,
                        outputTokens = event.usage.outputTokens ?: account.outputTokens,
                        totalTokens = event.usage.totalTokens ?: account.totalTokens,
                        responseBytes = maxOf(account.responseBytes, event.responseBytes),
                    )
                }
            }
        val next = state.copy(callAccounts = accounts)
        val violation = checkCallLimits(next, accounts.last())
        return if (violation != null) fail(next, violation) else step(state, next)
    }

    private fun onModelFinished(
        state: TurnState,
        event: TurnEvent.Model.Finished,
    ): TurnStep {
        if (state.phase != TurnPhase.RECEIVING_MODEL || state.activeCallId != event.callId) {
            return TurnStep.unchanged(state)
        }
        val accounts =
            state.callAccounts.map { account ->
                if (account.callId == event.callId) {
                    account.copy(responseBytes = maxOf(account.responseBytes, event.responseBytes))
                } else {
                    account
                }
            }
        val next = state.copy(callAccounts = accounts, activeCallId = null)
        val violation = checkCallLimits(next, accounts.last())
        return if (violation != null) {
            fail(next, violation)
        } else {
            when (val terminal = event.terminal) {
                is ModelTerminal.FinalText -> complete(next, terminal.finishReason)
                is ModelTerminal.Refusal -> complete(next, terminal.finishReason ?: "refusal")
                is ModelTerminal.ProtocolError -> fail(next, terminal.error)
                is ModelTerminal.ToolCalls -> startToolCalls(next, terminal.calls)
            }
        }
    }

    private fun onCallFailed(
        state: TurnState,
        event: TurnEvent.Model.CallFailed,
    ): TurnStep {
        val matchesCommitted =
            state.phase == TurnPhase.WAITING_MODEL && state.committedCallId == event.callId
        val matchesActive =
            state.phase == TurnPhase.RECEIVING_MODEL && state.activeCallId == event.callId
        if (!matchesCommitted && !matchesActive) return TurnStep.unchanged(state)
        val accounts =
            state.callAccounts.map { account ->
                if (account.callId == event.callId) {
                    account.copy(responseBytes = maxOf(account.responseBytes, event.responseBytes))
                } else {
                    account
                }
            }
        val next = state.copy(callAccounts = accounts, committedCallId = null, activeCallId = null)
        return fail(next, event.error)
    }

    private fun startToolCalls(
        state: TurnState,
        calls: List<ModelToolCall>,
    ): TurnStep {
        val pending =
            calls.map { call ->
                PendingToolCall(
                    toolCallId = call.toolCallId,
                    toolName = call.toolName,
                    toolVersion = call.toolVersion,
                    requiresApproval = call.requiresApproval,
                    state = ToolCallState.PENDING,
                )
            }
        val first = pending.first()
        val rest = pending.drop(1)
        return if (first.requiresApproval) {
            val awaiting = first.withState(ToolCallState.AWAITING_APPROVAL)
            val next = state.copy(phase = TurnPhase.WAITING_APPROVAL, pendingCalls = listOf(awaiting) + rest)
            step(state, next, listOf(TurnEffect.RequestApproval(awaiting)))
        } else {
            val running = first.withState(ToolCallState.RUNNING)
            val next = state.copy(phase = TurnPhase.RUNNING_TOOL, pendingCalls = listOf(running) + rest)
            step(state, next, listOf(TurnEffect.ExecuteToolCall(running)))
        }
    }

    private fun reduceTool(
        state: TurnState,
        event: TurnEvent.Tool,
    ): TurnStep =
        when (event) {
            is TurnEvent.Tool.CallApproved -> onCallApproved(state, event)
            is TurnEvent.Tool.CallDenied -> onCallDenied(state, event)
            is TurnEvent.Tool.ExecutionFinished -> onExecutionFinished(state, event)
            is TurnEvent.Tool.ResultsRecorded -> onResultsRecorded(state)
        }

    private fun onCallApproved(
        state: TurnState,
        event: TurnEvent.Tool.CallApproved,
    ): TurnStep {
        val current = state.pendingCalls.firstOrNull()
        if (state.phase != TurnPhase.WAITING_APPROVAL || current?.toolCallId != event.toolCallId) {
            return TurnStep.unchanged(state)
        }
        val running = current.withState(ToolCallState.RUNNING).copy(approvalId = event.approvalId)
        val next = state.copy(phase = TurnPhase.RUNNING_TOOL, pendingCalls = replaceFirst(state, running))
        return step(state, next, listOf(TurnEffect.ExecuteToolCall(running)))
    }

    private fun onCallDenied(
        state: TurnState,
        event: TurnEvent.Tool.CallDenied,
    ): TurnStep {
        val current = state.pendingCalls.firstOrNull()
        if (state.phase != TurnPhase.WAITING_APPROVAL || current?.toolCallId != event.toolCallId) {
            return TurnStep.unchanged(state)
        }
        val denied = current.withState(ToolCallState.DENIED)
        val outcome = ToolOutcome.Denied(event.reason)
        val next =
            state.copy(
                phase = TurnPhase.RECORDING_TOOL_RESULT,
                pendingCalls = replaceFirst(state, denied),
                recordedOutcomes = state.recordedOutcomes + RecordedToolOutcome(event.toolCallId, outcome),
                deniedCalls = state.deniedCalls + event.toolCallId,
            )
        return step(state, next, listOf(TurnEffect.RecordToolResult(event.toolCallId, outcome)))
    }

    private fun onExecutionFinished(
        state: TurnState,
        event: TurnEvent.Tool.ExecutionFinished,
    ): TurnStep {
        val current = state.pendingCalls.firstOrNull()
        if (state.phase != TurnPhase.RUNNING_TOOL || current?.toolCallId != event.toolCallId) {
            return TurnStep.unchanged(state)
        }
        val terminal =
            when (event.outcome) {
                is ToolOutcome.Succeeded -> ToolCallState.COMPLETED
                is ToolOutcome.Failed -> ToolCallState.FAILED
                is ToolOutcome.TimedOut -> ToolCallState.FAILED
                is ToolOutcome.Denied -> ToolCallState.DENIED
                is ToolOutcome.Cancelled -> ToolCallState.CANCELLED
            }
        val finished = current.withState(terminal)
        val next =
            state.copy(
                phase = TurnPhase.RECORDING_TOOL_RESULT,
                pendingCalls = replaceFirst(state, finished),
                recordedOutcomes = state.recordedOutcomes + RecordedToolOutcome(event.toolCallId, event.outcome),
            )
        return step(state, next, listOf(TurnEffect.RecordToolResult(event.toolCallId, event.outcome)))
    }

    /**
     * In RECORDING_TOOL_RESULT the queue head is the call that was just finished (terminal
     * state); this event removes it and either starts the next call of the same response or
     * re-enters the context/model loop.
     */
    private fun onResultsRecorded(state: TurnState): TurnStep {
        if (state.phase != TurnPhase.RECORDING_TOOL_RESULT) return TurnStep.unchanged(state)
        val rest = state.pendingCalls.drop(1)
        val nextCall = rest.firstOrNull()
        return if (nextCall == null) {
            val next = state.copy(phase = TurnPhase.BUILDING_CONTEXT, pendingCalls = emptyList())
            step(state, next, listOf(TurnEffect.BuildContext))
        } else if (nextCall.requiresApproval) {
            val awaiting = nextCall.withState(ToolCallState.AWAITING_APPROVAL)
            val next =
                state.copy(
                    phase = TurnPhase.WAITING_APPROVAL,
                    pendingCalls = listOf(awaiting) + rest.drop(1),
                )
            step(state, next, listOf(TurnEffect.RequestApproval(awaiting)))
        } else {
            val running = nextCall.withState(ToolCallState.RUNNING)
            val next =
                state.copy(
                    phase = TurnPhase.RUNNING_TOOL,
                    pendingCalls = listOf(running) + rest.drop(1),
                )
            step(state, next, listOf(TurnEffect.ExecuteToolCall(running)))
        }
    }

    private fun replaceFirst(
        state: TurnState,
        updated: PendingToolCall,
    ): List<PendingToolCall> = state.pendingCalls.mapIndexed { index, call -> if (index == 0) updated else call }

    private fun complete(
        prev: TurnState,
        finishReason: String?,
    ): TurnStep {
        val next = prev.copy(phase = TurnPhase.COMPLETED, finishReason = finishReason)
        return step(prev, next, listOf(TurnEffect.CompleteTurn(finishReason)))
    }

    private fun fail(
        prev: TurnState,
        error: HelixError,
    ): TurnStep {
        val next =
            prev.copy(
                phase = TurnPhase.FAILED,
                error = error,
                committedCallId = null,
                activeCallId = null,
            )
        return step(prev, next, listOf(TurnEffect.FailTurn(error)))
    }

    private fun checkCallLimits(
        state: TurnState,
        account: CallTokenAccount,
    ): HelixError? {
        val budgets = state.budgets
        val input = account.inputTokens
        if (input != null && input > budgets.maxInputTokens) {
            return providerViolation(
                state,
                "maxInputTokens",
                "Provider reported input above the per-call input budget.",
            )
        }
        val output = account.outputTokens
        return if (output != null && output > budgets.maxOutputTokens) {
            providerViolation(
                state,
                "maxOutputTokens",
                "Provider reported output above the per-call output budget.",
            )
        } else {
            null
        }
    }

    private fun budgetError(
        state: TurnState,
        limit: String,
        message: String,
    ): HelixError =
        HelixError(
            code = ErrorCode.POLICY,
            userMessage = message,
            retryable = false,
            safeDetails = mapOf("limit" to limit),
            correlationId = state.correlationId,
        )

    private fun providerViolation(
        state: TurnState,
        limit: String,
        message: String,
    ): HelixError =
        HelixError(
            code = ErrorCode.INTERNAL,
            userMessage = message,
            retryable = false,
            safeDetails = mapOf("limit" to limit),
            correlationId = state.correlationId,
        )

    /** Records the step, enforcing the phase transition rules and state invariants. */
    private fun step(
        prev: TurnState,
        next: TurnState,
        effects: List<TurnEffect> = emptyList(),
    ): TurnStep {
        if (prev.phase != next.phase) {
            require(prev.phase.canTransitionTo(next.phase)) {
                "illegal turn transition ${prev.phase} -> ${next.phase}"
            }
        }
        verify(next)
        return TurnStep(next, effects)
    }

    private fun verify(state: TurnState) {
        val phase = state.phase
        if (phase == TurnPhase.FAILED) {
            require(state.error != null) { "FAILED turn requires an error" }
        }
        if (phase == TurnPhase.COMPLETED || phase == TurnPhase.CANCELLED) {
            require(state.error == null && state.finishReason != null) {
                "COMPLETED/CANCELLED turn requires a finish reason and no error"
            }
        }
        if (!phase.isTerminal) {
            require(state.error == null && state.finishReason == null) {
                "non-terminal turn must not carry finalization fields"
            }
        }
        require(state.committedCallId == null || state.activeCallId == null) {
            "at most one of committedCallId/activeCallId may be set"
        }
        if (state.committedCallId != null) {
            require(phase == TurnPhase.WAITING_MODEL) {
                "committedCallId is only valid in WAITING_MODEL"
            }
        }
        if (state.activeCallId != null) {
            require(phase == TurnPhase.RECEIVING_MODEL) {
                "activeCallId is only valid in RECEIVING_MODEL"
            }
        }
        if (state.pendingCalls.isNotEmpty()) {
            require(phase in TOOL_PHASES) { "pendingCalls is only valid in tool phases" }
        }
    }

    private val TOOL_PHASES =
        setOf(
            TurnPhase.WAITING_APPROVAL,
            TurnPhase.RUNNING_TOOL,
            TurnPhase.RECORDING_TOOL_RESULT,
            TurnPhase.CANCELLING,
            TurnPhase.INTERRUPTED,
        )
}
