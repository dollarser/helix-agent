package com.helix.core.agent

import com.helix.core.model.GoalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderPlanTest {
    @Test
    fun schedulesAheadOfCheckpoint() {
        val plan = ReminderPlan.forCheckpoint(nowEpochMillis = 1_000L, checkpoint = Checkpoint(10_000L))
        assertFalse(plan.skip)
        assertEquals(9_000, plan.delayMillis)
    }

    @Test
    fun pastCheckpointSchedulesImmediately() {
        // A deferral (Doze/force-stop) that pushes the reminder past the checkpoint must not
        // be dropped: delay clamps to zero, i.e. schedule now.
        val plan = ReminderPlan.forCheckpoint(nowEpochMillis = 10_000L, checkpoint = Checkpoint(1_000L))
        assertFalse(plan.skip)
        assertEquals(0L, plan.delayMillis)
    }

    @Test
    fun missingCheckpointSkips() {
        val plan = ReminderPlan.forCheckpoint(nowEpochMillis = 1_000L, checkpoint = null)
        assertTrue(plan.skip)
    }

    @Test
    fun onlyCheckpointableStatesWantReminders() {
        for (state in GoalState.entries) {
            val wants = state in setOf(GoalState.RUNNING, GoalState.PAUSED, GoalState.INPUT_REQUIRED)
            val plan = ReminderPlan.forGoal(1_000L, state, Checkpoint(10_000L))
            assertEquals("$state should not skip", !wants, plan.skip)
        }
    }

    @Test
    fun checkpointedPausedGoalStillReminds() {
        // After a process-death park the notification is a legitimate wake source (NOTIFICATION_ACTION).
        val plan = ReminderPlan.forGoal(5_000L, GoalState.PAUSED, Checkpoint(20_000L))
        assertFalse(plan.skip)
        assertEquals(15_000, plan.delayMillis)
    }
}
