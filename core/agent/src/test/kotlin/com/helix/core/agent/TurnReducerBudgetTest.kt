package com.helix.core.agent

import com.helix.core.model.ErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.helix.core.model.TurnState as Phase

class TurnReducerBudgetTest {
    /** Runs one full tool round so the turn returns to BUILDING_CONTEXT for the next model call. */
    private fun oneModelRoundWithTool(
        state: TurnState,
        callNumber: Int,
    ): TurnState {
        val ready = reduce(state, TurnEvent.Lifecycle.ContextReady(Fixtures.call(callNumber), 1000)).state
        val receiving = reduce(ready, TurnEvent.Model.StreamStarted(Fixtures.call(callNumber))).state
        val toolCall = Fixtures.modelToolCall(1, false)
        val running =
            reduce(
                receiving,
                TurnEvent.Model.Finished(activeCall(receiving), 400, ModelTerminal.ToolCalls(listOf(toolCall))),
            ).state
        val recorded = reduce(running, TurnEvent.Tool.ExecutionFinished(Fixtures.tool(1), Fixtures.success())).state
        return reduce(recorded, TurnEvent.Tool.ResultsRecorded).state
    }

    @Test
    fun stepBudgetExhaustionFailsTurn() {
        var state = Fixtures.newTurn(Fixtures.budgets(maxSteps = 1, maxModelCalls = 8))
        state = reduce(state, TurnEvent.Lifecycle.TurnSubmitted).state
        state = oneModelRoundWithTool(state, 1)
        assertEquals(Phase.BUILDING_CONTEXT, state.phase)
        val step =
            TurnReducer.reduce(state, TurnEvent.Lifecycle.ContextReady(Fixtures.call(2), 1000))
        assertEquals(Phase.FAILED, step.state.phase)
        val error = step.state.error
        assertNotNull(error)
        assertEquals(ErrorCode.POLICY, error?.code)
        assertEquals("maxSteps", error?.safeDetails?.get("limit"))
        val effect = step.effects.single() as TurnEffect.FailTurn
        assertEquals(ErrorCode.POLICY, effect.error.code)
    }

    @Test
    fun modelCallBudgetExhaustionFailsTurn() {
        var state = Fixtures.newTurn(Fixtures.budgets(maxSteps = 8, maxModelCalls = 1))
        state = reduce(state, TurnEvent.Lifecycle.TurnSubmitted).state
        state = oneModelRoundWithTool(state, 1)
        val step = TurnReducer.reduce(state, TurnEvent.Lifecycle.ContextReady(Fixtures.call(2), 1000))
        assertEquals(Phase.FAILED, step.state.phase)
        assertEquals(
            "maxModelCalls",
            step.state.error
                ?.safeDetails
                ?.get("limit"),
        )
    }

    @Test
    fun totalTokenBudgetUsesConservativeEstimateWhenUsageMissing() {
        var state = Fixtures.newTurn(Fixtures.budgets(maxTotalTokens = 300))
        state = reduce(state, TurnEvent.Lifecycle.TurnSubmitted).state
        state = oneModelRoundWithTool(state, 1)
        // No usage was reported: used tokens are the byte estimates of request (1000B -> 250)
        // and response (400B -> 100), i.e. 350. If missing usage were treated as zero the
        // used total would be 0 and the next commit (0 + 1 <= 300) would succeed, so this
        // assertion plus the FAILED result below prove the conservative estimate is counted.
        assertEquals(350, state.usedTotalTokens)
        val step = TurnReducer.reduce(state, TurnEvent.Lifecycle.ContextReady(Fixtures.call(2), 1))
        assertEquals(Phase.FAILED, step.state.phase)
        assertEquals(
            "maxTotalTokens",
            step.state.error
                ?.safeDetails
                ?.get("limit"),
        )
    }

    @Test
    fun totalTokenBudgetSucceedsWhenUsageFits() {
        var state = Fixtures.newTurn(Fixtures.budgets(maxTotalTokens = 14_000))
        state = reduce(state, TurnEvent.Lifecycle.TurnSubmitted).state
        state = oneModelRoundWithTool(state, 1)
        val step = TurnReducer.reduce(state, TurnEvent.Lifecycle.ContextReady(Fixtures.call(2), 1000))
        assertEquals(Phase.WAITING_MODEL, step.state.phase)
        assertTrue(step.effects.isNotEmpty())
    }

    @Test
    fun perCallInputBudgetIsCheckedAtCommit() {
        val state = Fixtures.newTurn(Fixtures.budgets(maxInputTokens = 100))
        val building = reduce(state, TurnEvent.Lifecycle.TurnSubmitted).state
        // 500 bytes estimate to 125 tokens, above the 100-token per-call input budget.
        val step = TurnReducer.reduce(building, TurnEvent.Lifecycle.ContextReady(Fixtures.call(1), 500))
        assertEquals(Phase.FAILED, step.state.phase)
        assertEquals(
            "maxInputTokens",
            step.state.error
                ?.safeDetails
                ?.get("limit"),
        )
    }

    @Test
    fun reportedOutputAbovePerCallCapFailsTurn() {
        val budgets = Fixtures.budgets(maxOutputTokens = 10)
        var state = Fixtures.newTurn(budgets)
        state = reduce(state, TurnEvent.Lifecycle.TurnSubmitted).state
        val waiting = reduce(state, TurnEvent.Lifecycle.ContextReady(Fixtures.call(1), 1000)).state
        val receiving = reduce(waiting, TurnEvent.Model.StreamStarted(Fixtures.call(1))).state
        val step =
            TurnReducer.reduce(
                receiving,
                TurnEvent.Model.UsageReported(
                    Fixtures.call(1),
                    Fixtures.usage(input = 100, output = 50, total = 150),
                    400,
                ),
            )
        assertEquals(Phase.FAILED, step.state.phase)
        val error = step.state.error
        assertNotNull(error)
        assertEquals(ErrorCode.INTERNAL, error?.code)
        assertEquals("maxOutputTokens", error?.safeDetails?.get("limit"))
    }

    @Test
    fun exactBudgetBoundaryIsAllowed() {
        // maxTotalTokens = 400; request of 1600 bytes estimates to exactly 400 tokens.
        val state = Fixtures.newTurn(Fixtures.budgets(maxTotalTokens = 400))
        val building = reduce(state, TurnEvent.Lifecycle.TurnSubmitted).state
        val step = TurnReducer.reduce(building, TurnEvent.Lifecycle.ContextReady(Fixtures.call(1), 1600))
        assertEquals(Phase.WAITING_MODEL, step.state.phase)
    }

    @Test
    fun startModelCallCarriesEffectiveOutputCap() {
        var state = Fixtures.newTurn(Fixtures.budgets(maxOutputTokens = 2_000, maxTotalTokens = 14_000))
        state = reduce(state, TurnEvent.Lifecycle.TurnSubmitted).state
        val step = reduce(state, TurnEvent.Lifecycle.ContextReady(Fixtures.call(1), 1000))
        val start = step.effects.single() as TurnEffect.StartModelCall
        // used after commit = 250 (request estimate); headroom = 14000 - 250 = 13750.
        assertEquals(2_000, start.maxOutputTokens)
    }
}
