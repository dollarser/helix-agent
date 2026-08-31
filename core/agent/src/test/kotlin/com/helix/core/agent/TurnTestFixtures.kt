package com.helix.core.agent

import com.helix.core.model.ApprovalId
import com.helix.core.model.CorrelationId
import com.helix.core.model.ErrorCode
import com.helix.core.model.HelixError
import com.helix.core.model.ModelCallId
import com.helix.core.model.SessionId
import com.helix.core.model.ToolCallId
import com.helix.core.model.ToolName
import com.helix.core.model.ToolVersion
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnId
import org.junit.Assert.fail
import com.helix.core.model.TurnState as Phase

/**
 * Reified `assertThrows` for JUnit4 (the Kotlin extension is provided by kotlin-test, which
 * this module does not depend on). Mirrors kotlin.test semantics: returns the thrown instance.
 */
internal inline fun <reified T : Throwable> assertThrows(
    message: String? = null,
    block: () -> Unit,
): T {
    try {
        block()
    } catch (t: Throwable) {
        if (t is T) return t
        val detail = if (message.isNullOrBlank()) "" else "$message: "
        fail("${detail}expected ${T::class.java.simpleName} but got ${t::class.java.name}: ${t.message}")
    }
    val detail = if (message.isNullOrBlank()) "" else "$message: "
    fail("${detail}expected ${T::class.java.simpleName} but nothing was thrown")
    throw AssertionError("unreachable")
}

internal object Fixtures {
    val session = SessionId("session-1")
    val turn = TurnId("turn-1")
    val correlation = CorrelationId("corr-1")

    fun budgets(
        maxSteps: Int = 8,
        maxModelCalls: Int = 4,
        maxInputTokens: Long = 12_000,
        maxOutputTokens: Long = 2_000,
        maxTotalTokens: Long = 14_000,
    ): TurnBudgets = TurnBudgets(maxSteps, maxModelCalls, maxInputTokens, maxOutputTokens, maxTotalTokens)

    fun newTurn(budgets: TurnBudgets = budgets()): TurnState = TurnState.initial(session, turn, correlation, budgets)

    fun call(n: Int = 1): ModelCallId = ModelCallId("model-call-$n")

    fun tool(n: Int = 1): ToolCallId = ToolCallId("tool-call-$n")

    fun approval(n: Int = 1): ApprovalId = ApprovalId("approval-$n")

    fun modelToolCall(
        n: Int,
        requiresApproval: Boolean = false,
    ): ModelToolCall = ModelToolCall(tool(n), ToolName("read"), ToolVersion(1), requiresApproval)

    fun success(summary: String = "read ok"): ToolOutcome.Succeeded =
        ToolOutcome.Succeeded(outputRef = null, summary = summary, verified = true)

    fun usage(
        input: Long?,
        output: Long?,
        total: Long?,
    ): TokenUsage = TokenUsage(input, output, total)

    fun error(
        code: ErrorCode = ErrorCode.NETWORK,
        message: String = "provider network failure",
        retryable: Boolean = false,
    ): HelixError = HelixError(code, message, retryable, emptyMap(), correlation)
}

/** The non-null active model call id; tests only call this in RECEIVING_MODEL. */
internal fun activeCall(state: TurnState): ModelCallId =
    state.activeCallId ?: error("state has no active model call in phase ${state.phase}")

/** Applies [event], failing the test if it is ignored. */
internal fun reduce(
    state: TurnState,
    event: TurnEvent,
): TurnStep {
    val step = TurnReducer.reduce(state, event)
    if (step.ignored) fail("event $event was ignored in phase ${state.phase}")
    return step
}

private fun toolCallsResponse(needsApproval: Boolean): ModelTerminal.ToolCalls =
    ModelTerminal.ToolCalls(listOf(Fixtures.modelToolCall(1, needsApproval)))

/**
 * Drives a fresh turn to the given phase with the default generous budgets. Tool phases use a
 * deterministic model response: one call for WAITING_APPROVAL/RUNNING_TOOL, a succeeded call
 * for RECORDING_TOOL_RESULT.
 */
internal fun driveTo(
    phase: Phase,
    budgets: TurnBudgets = Fixtures.budgets(),
): TurnState {
    val callId = Fixtures.call(1)
    return when (phase) {
        Phase.CREATED -> {
            Fixtures.newTurn(budgets)
        }

        Phase.BUILDING_CONTEXT -> {
            reduce(Fixtures.newTurn(budgets), TurnEvent.Lifecycle.TurnSubmitted).state
        }

        Phase.WAITING_MODEL -> {
            reduce(
                driveTo(Phase.BUILDING_CONTEXT, budgets),
                TurnEvent.Lifecycle.ContextReady(callId, 1000),
            ).state
        }

        Phase.RECEIVING_MODEL -> {
            reduce(driveTo(Phase.WAITING_MODEL, budgets), TurnEvent.Model.StreamStarted(callId)).state
        }

        Phase.WAITING_APPROVAL -> {
            reduce(driveTo(Phase.RECEIVING_MODEL, budgets), finished(callId, toolCallsResponse(true))).state
        }

        Phase.RUNNING_TOOL -> {
            reduce(driveTo(Phase.RECEIVING_MODEL, budgets), finished(callId, toolCallsResponse(false))).state
        }

        Phase.RECORDING_TOOL_RESULT -> {
            reduce(
                driveTo(Phase.RUNNING_TOOL, budgets),
                TurnEvent.Tool.ExecutionFinished(Fixtures.tool(1), Fixtures.success()),
            ).state
        }

        Phase.CANCELLING -> {
            reduce(driveTo(Phase.CREATED, budgets), TurnEvent.Lifecycle.CancelRequested).state
        }

        Phase.INTERRUPTED -> {
            TurnReducer.afterProcessDeath(driveTo(Phase.RECEIVING_MODEL, budgets))
        }

        Phase.COMPLETED -> {
            reduce(driveTo(Phase.RECEIVING_MODEL, budgets), finished(callId, ModelTerminal.FinalText("stop"))).state
        }

        Phase.FAILED -> {
            reduce(
                driveTo(Phase.WAITING_MODEL, budgets),
                TurnEvent.Model.CallFailed(callId, 0, Fixtures.error()),
            ).state
        }

        Phase.CANCELLED -> {
            reduce(
                reduce(Fixtures.newTurn(budgets), TurnEvent.Lifecycle.CancelRequested).state,
                TurnEvent.Lifecycle.CancelFinished(null),
            ).state
        }
    }
}

private fun finished(
    callId: ModelCallId,
    terminal: ModelTerminal,
): TurnEvent.Model.Finished = TurnEvent.Model.Finished(callId, 400, terminal)
