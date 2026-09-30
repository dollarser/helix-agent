package com.helix.core.agent

import com.helix.core.model.GoalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalRecoveryEndedTest {
    @Test fun recoveryClosureFailsParkedGoalsWithoutStartingAnotherRun() {
        val running = reduceGoal(runningGoal(), GoalEvent.CheckpointScheduled(Checkpoint(99_000L))).state
        val error = GoalFixtures.error()
        listOf(GoalState.RUNNING, GoalState.PAUSED, GoalState.BLOCKED, GoalState.INPUT_REQUIRED).forEach { state ->
            val before = running.copy(state = state, currentWakeMillis = 0L)
            val step = GoalReducer.reduce(before, GoalEvent.RecoveryEnded(error))
            assertEquals(GoalState.FAILED, step.state.state)
            assertEquals(error, step.state.error)
            assertNull(step.state.nextCheckpoint)
            assertEquals(before.runCount, step.state.runCount)
            assertEquals(before.totalTokens, step.state.totalTokens)
            assertEquals(listOf(GoalEffect.GoalFailed(error), GoalEffect.ReminderCancelled), step.effects)
            assertTrue(GoalReducer.reduce(step.state, GoalEvent.RecoveryEnded(error)).ignored)
        }
    }

    @Test fun recoveryDoesNotFailAnUnstartedOrAlreadyCompletedGoal() {
        val draft = GoalFixtures.newGoal()
        val ready = reduceGoal(draft, GoalEvent.Ready(null, null)).state
        val completed = reduceGoal(runningGoal(), GoalEvent.CompleteRequested).state
        listOf(draft, ready, completed).forEach { goal ->
            val step = GoalReducer.reduce(goal, GoalEvent.RecoveryEnded(GoalFixtures.error()))
            assertTrue(step.ignored)
            assertEquals(goal, step.state)
        }
    }
}
