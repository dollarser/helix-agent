package com.helix.core.agent

import com.helix.core.model.GoalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalReducerBudgetTest {
    @Test fun usageAcrossAllHistoricalLimitsRemainsObservedWithoutStopping() {
        var goal = runningGoal()
        repeat(3) {
            goal = reduceGoal(goal, GoalEvent.WakeUsageReported(30, 60, 1000, 5000)).state
            assertEquals(GoalState.RUNNING, goal.state)
            goal = reduceGoal(goal, GoalEvent.RunFinished).state
            assertEquals(GoalState.PAUSED, goal.state)
            goal = reduceGoal(goal, GoalEvent.Continued(GoalWakeReason.USER_OPEN)).state
        }
        assertEquals(90, goal.modelCalls)
        assertEquals(180, goal.toolCalls)
        assertEquals(3000L, goal.totalTokens)
        assertEquals(15000L, goal.runTimeMillis)
    }

    @Test fun usageOutsideRunningIsIgnored() {
        assertTrue(GoalReducer.reduce(GoalFixtures.newGoal(), GoalEvent.WakeUsageReported(1, 1, 1, 1)).ignored)
    }

    @Test fun parkedRoundAllowanceCanChangeWithoutResettingUsageOrStartingWork() {
        val running = reduceGoal(runningGoal(), GoalEvent.WakeUsageReported(3, 8, 999, 5000)).state
        assertTrue(
            GoalReducer.reduce(running, GoalEvent.BudgetsUpdated(running.budgets.copy(maxGoalRounds = 7))).ignored,
        )
        val paused = reduceGoal(running, GoalEvent.RunFinished).state
        val changed = reduceGoal(paused, GoalEvent.BudgetsUpdated(paused.budgets.copy(maxGoalRounds = 7)))
        assertEquals(7, changed.state.budgets.maxGoalRounds)
        assertEquals(999L, changed.state.totalTokens)
        assertEquals(GoalState.PAUSED, changed.state.state)
        assertTrue(changed.effects.isEmpty())
    }

    @Test fun historicalZeroResourceAllowanceDoesNotBlockExplicitContinuation() {
        val paused = reduceGoal(runningGoal(), GoalEvent.RunFinished).state
        val historical =
            paused.copy(
                budgets = paused.budgets.copy(maxTotalTokens = 0, maxDurationMillis = 0, maxWakeDurationMillis = 0),
            )
        assertEquals(
            GoalState.RUNNING,
            reduceGoal(historical, GoalEvent.Continued(GoalWakeReason.USER_OPEN)).state.state,
        )
    }

    @Test fun abnormalFailuresNeverAutoRetryRegardlessOfOldRetryAllowance() {
        for (retryable in listOf(true, false)) {
            val error = GoalFixtures.error("provider failure", retryable)
            val failed = reduceGoal(runningGoal(GoalFixtures.budgets(maxRetries = 99)), GoalEvent.WakeFailed(error))
            assertEquals(GoalState.FAILED, failed.state.state)
            assertEquals(error, failed.state.error)
            assertEquals(0, failed.state.retries)
            assertTrue(failed.effects.none { it is GoalEffect.RetryWake })
            assertTrue(failed.effects.any { it is GoalEffect.GoalFailed })
        }
    }

    @Test fun repeatedTerminalFailureIsIgnored() {
        val failure = GoalEvent.WakeFailed(GoalFixtures.error())
        val failed = reduceGoal(runningGoal(), failure).state
        assertTrue(GoalReducer.reduce(failed, failure).ignored)
    }

    @Test fun blockerStillRequiresExplicitResolution() {
        val blocked = reduceGoal(runningGoal(), GoalEvent.Blocked).state
        assertTrue(GoalReducer.reduce(blocked, GoalEvent.Continued(GoalWakeReason.USER_OPEN)).ignored)
        val resolved = reduceGoal(blocked, GoalEvent.BlockerResolved).state
        assertEquals(GoalState.PAUSED, resolved.state)
    }
}
