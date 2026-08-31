package com.helix.core.agent

import com.helix.core.model.GoalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalReducerBudgetTest {
    @Test
    fun usageWithinBudgetStaysRunning() {
        val goal = runningGoal()
        val step = reduceGoal(goal, GoalEvent.WakeUsageReported(2, 5, 100, 4_000))
        assertEquals(GoalState.RUNNING, step.state.state)
        assertEquals(2, step.state.modelCalls)
        assertEquals(100, step.state.totalTokens)
        assertEquals(4_000, step.state.currentWakeMillis)
        assertEquals(4_000, step.state.runTimeMillis)
        assertTrue(step.effects.isEmpty())
    }

    @Test
    fun eachSingleWakeBudgetExhaustionParksGoal() {
        val cases =
            listOf(
                "maxModelCalls" to GoalEvent.WakeUsageReported(3, 0, 0, 0),
                "maxToolCalls" to GoalEvent.WakeUsageReported(0, 6, 0, 0),
                "maxTotalTokens" to GoalEvent.WakeUsageReported(0, 0, 101, 0),
                "maxWakeDurationMillis" to GoalEvent.WakeUsageReported(0, 0, 0, 4_001),
            )
        for ((limit, event) in cases) {
            val step = reduceGoal(runningGoal(), event)
            assertEquals("$limit exhaustion", GoalState.PAUSED, step.state.state)
            val effect = step.effects.single() as GoalEffect.BudgetExhausted
            assertEquals(limit, effect.limit)
            assertEquals(0L, step.state.currentWakeMillis)
        }
    }

    @Test
    fun totalDurationExhaustsAcrossWakes() {
        // Three wakes of 4000ms each (each at the single-wake limit) separated by normal
        // run finish + explicit continue; the third pushes the goal-lifetime total to
        // 12000 > 10000, so maxDurationMillis is the first exhausted budget.
        var goal = runningGoal()
        goal = reduceGoal(goal, GoalEvent.WakeUsageReported(0, 0, 0, 4_000)).state
        goal = reduceGoal(goal, GoalEvent.RunFinished).state
        goal = reduceGoal(goal, GoalEvent.Continued(GoalWakeReason.USER_OPEN)).state
        goal = reduceGoal(goal, GoalEvent.WakeUsageReported(0, 0, 0, 4_000)).state
        goal = reduceGoal(goal, GoalEvent.RunFinished).state
        goal = reduceGoal(goal, GoalEvent.Continued(GoalWakeReason.USER_OPEN)).state
        assertEquals(GoalState.RUNNING, goal.state)
        val step = reduceGoal(goal, GoalEvent.WakeUsageReported(0, 0, 0, 4_000))
        assertEquals(GoalState.PAUSED, step.state.state)
        assertEquals("maxDurationMillis", (step.effects.single() as GoalEffect.BudgetExhausted).limit)
    }

    @Test
    fun cumulativeUsageAcrossWakesExhaustsBudget() {
        // Wake 1 stays within every budget; wake 2 pushes tokens to 50 + 51 = 101 > 100.
        // (Model calls 1 + 1 = 2 stay at the limit, so tokens are the first exhausted budget.)
        var goal = runningGoal()
        goal = reduceGoal(goal, GoalEvent.WakeUsageReported(1, 0, 50, 1_000)).state
        assertEquals(GoalState.RUNNING, goal.state)
        val step = reduceGoal(goal, GoalEvent.WakeUsageReported(1, 0, 51, 1_000))
        assertEquals(GoalState.PAUSED, step.state.state)
        assertEquals("maxTotalTokens", (step.effects.single() as GoalEffect.BudgetExhausted).limit)
    }

    @Test
    fun exhaustionReportsFirstLimitInFixedOrder() {
        // Both model calls and tokens are exhausted; the fixed order reports maxModelCalls.
        val step = reduceGoal(runningGoal(), GoalEvent.WakeUsageReported(3, 0, 101, 0))
        assertEquals("maxModelCalls", (step.effects.single() as GoalEffect.BudgetExhausted).limit)
    }

    @Test
    fun usageOutsideRunningIsIgnored() {
        val ready = reduceGoal(GoalFixtures.newGoal(), GoalEvent.Ready(null, null)).state
        val step = GoalReducer.reduce(ready, GoalEvent.WakeUsageReported(1, 0, 0, 0))
        assertTrue(step.ignored)
    }

    @Test
    fun budgetUpdateOnlyWhileParked() {
        val running = runningGoal()
        val ignoredRunning =
            GoalReducer.reduce(running, GoalEvent.BudgetsUpdated(GoalFixtures.budgets(maxModelCalls = 9)))
        assertTrue(ignoredRunning.ignored)

        var paused = runningGoal()
        paused = reduceGoal(paused, GoalEvent.WakeUsageReported(3, 0, 0, 0)).state
        assertEquals(GoalState.PAUSED, paused.state)
        val updated = reduceGoal(paused, GoalEvent.BudgetsUpdated(GoalFixtures.budgets(maxModelCalls = 9)))
        assertEquals(9, updated.state.budgets.maxModelCalls)
        assertEquals(GoalState.PAUSED, updated.state.state)

        val inputRequired = reduceGoal(runningGoal(), GoalEvent.InputRequired("r")).state
        val fromInput =
            reduceGoal(inputRequired, GoalEvent.BudgetsUpdated(GoalFixtures.budgets(maxModelCalls = 4)))
        assertEquals(4, fromInput.state.budgets.maxModelCalls)
    }

    @Test
    fun continuedIsIgnoredWhenNoModelCallsRemain() {
        // The single model call of the goal was used in the run; the goal parks in PAUSED and
        // an explicit continue must be IGNORED (stay parked), not start a run the coordinator
        // cannot budget (StartRun remainders would be 0/negative — an illegal TurnBudgets).
        val running = runningGoal(GoalFixtures.budgets(maxModelCalls = 1))
        val used = reduceGoal(running, GoalEvent.WakeUsageReported(1, 0, 0, 0)).state
        val parked = reduceGoal(used, GoalEvent.RunFinished).state
        assertEquals(GoalState.PAUSED, parked.state)
        val step = GoalReducer.reduce(parked, GoalEvent.Continued(GoalWakeReason.USER_OPEN))
        assertTrue("continue with zero remaining model calls must be ignored", step.ignored)
        assertEquals(parked, step.state)
    }

    @Test
    fun budgetExtensionUnblocksContinue() {
        val running = runningGoal(GoalFixtures.budgets(maxModelCalls = 1))
        val used = reduceGoal(running, GoalEvent.WakeUsageReported(1, 0, 0, 0)).state
        val parked = reduceGoal(used, GoalEvent.RunFinished).state
        assertTrue(GoalReducer.reduce(parked, GoalEvent.Continued(GoalWakeReason.USER_OPEN)).ignored)
        val extended = reduceGoal(parked, GoalEvent.BudgetsUpdated(GoalFixtures.budgets(maxModelCalls = 2))).state
        val continued = reduceGoal(extended, GoalEvent.Continued(GoalWakeReason.USER_OPEN))
        assertEquals(GoalState.RUNNING, continued.state.state)
    }

    @Test
    fun budgetsUpdatedBelowCurrentUsageIsIgnored() {
        // Shrinking the budget below what the goal already consumed would park it on the very
        // next wake report and make the StartRun remainders negative — ignored, not applied.
        val running = runningGoal(GoalFixtures.budgets(maxModelCalls = 2))
        val used = reduceGoal(running, GoalEvent.WakeUsageReported(2, 0, 0, 0)).state
        val parked = reduceGoal(used, GoalEvent.RunFinished).state
        val step = GoalReducer.reduce(parked, GoalEvent.BudgetsUpdated(GoalFixtures.budgets(maxModelCalls = 1)))
        assertTrue("budget below current usage must be ignored", step.ignored)
        assertEquals(parked, step.state)
    }

    @Test
    fun budgetsUpdatedBelowUsedRetriesIsIgnored() {
        // A goal that already consumed one retry must be protected from a maxRetries shrink
        // below the used amount: the old gate checked only five of the six budget dimensions,
        // let the shrink through, and the next retryable WakeFailed then FAILED a goal that
        // was still within its originally granted budget.
        val running = runningGoal(GoalFixtures.budgets(maxRetries = 2))
        val retried = reduceGoal(running, GoalEvent.WakeFailed(GoalFixtures.error("x", retryable = true))).state
        assertEquals(1, retried.retries)
        val parked = reduceGoal(retried, GoalEvent.RunFinished).state
        assertEquals(GoalState.PAUSED, parked.state)
        val step = GoalReducer.reduce(parked, GoalEvent.BudgetsUpdated(GoalFixtures.budgets(maxRetries = 0)))
        assertTrue("shrinking maxRetries below used retries must be ignored", step.ignored)
        assertEquals(parked, step.state)
        // Boundary: shrinking to exactly the used amount leaves no negative remainder and is
        // accepted (retries 1 of 1 is legal).
        val boundary = reduceGoal(parked, GoalEvent.BudgetsUpdated(GoalFixtures.budgets(maxRetries = 1)))
        assertEquals(1, boundary.state.budgets.maxRetries)
    }

    @Test
    fun wakeFailureRetriesWithinBudget() {
        val goal = runningGoal()
        val retryable = GoalFixtures.error("provider 500", retryable = true)
        val step = reduceGoal(goal, GoalEvent.WakeFailed(retryable))
        assertEquals(GoalState.RUNNING, step.state.state)
        assertEquals(1, step.state.retries)
        val effect = step.effects.single() as GoalEffect.RetryWake
        assertEquals(1, effect.attempt)
    }

    @Test
    fun wakeFailureFailsGoalWhenRetriesExhausted() {
        val goal = runningGoal()
        val retryable = GoalFixtures.error("provider 500", retryable = true)
        val first = reduceGoal(goal, GoalEvent.WakeFailed(retryable)).state
        val second = reduceGoal(first, GoalEvent.WakeFailed(retryable))
        assertEquals(GoalState.FAILED, second.state.state)
        assertEquals(retryable, second.state.error)
        val effect = second.effects.single() as GoalEffect.GoalFailed
        assertEquals(retryable, effect.error)
    }

    @Test
    fun nonRetryableFailureFailsGoalImmediately() {
        val goal = runningGoal()
        val fatal = GoalFixtures.error("provider rejected", retryable = false)
        val step = reduceGoal(goal, GoalEvent.WakeFailed(fatal))
        assertEquals(GoalState.FAILED, step.state.state)
        assertEquals(fatal, step.state.error)
        assertEquals(0, step.state.retries)
    }

    @Test
    fun zeroRetriesMeansFirstFailureFailsGoal() {
        val goal = runningGoal(GoalFixtures.budgets(maxRetries = 0))
        val step = reduceGoal(goal, GoalEvent.WakeFailed(GoalFixtures.error("x", retryable = true)))
        assertEquals(GoalState.FAILED, step.state.state)
    }

    @Test
    fun wakeFailureOutsideRunningIsIgnored() {
        val step =
            GoalReducer.reduce(GoalFixtures.newGoal(), GoalEvent.WakeFailed(GoalFixtures.error()))
        assertTrue(step.ignored)
    }
}
