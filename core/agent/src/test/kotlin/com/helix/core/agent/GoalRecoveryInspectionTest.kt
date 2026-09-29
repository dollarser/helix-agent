package com.helix.core.agent

import com.helix.core.model.GoalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalRecoveryInspectionTest {
    @Test fun inspectionPreservesAccumulatedUsageAndNormalBlockedAdmission() {
        val blocked =
            runningGoal().copy(
                state = GoalState.BLOCKED,
                modelCalls = 1,
                toolCalls = 2,
                totalTokens = 30,
                runTimeMillis = 100,
            )
        assertTrue(GoalReducer.reduce(blocked, GoalEvent.Continued(GoalWakeReason.USER_OPEN)).ignored)
        val recovery = GoalReducer.reduce(blocked, GoalEvent.RecoveryInspection)
        assertFalse(recovery.ignored)
        assertEquals(GoalState.RUNNING, recovery.state.state)
        assertEquals(blocked.modelCalls, recovery.state.modelCalls)
        assertEquals(blocked.toolCalls, recovery.state.toolCalls)
        assertEquals(blocked.totalTokens, recovery.state.totalTokens)
        assertEquals(blocked.runTimeMillis, recovery.state.runTimeMillis)
        assertEquals(blocked.runCount + 1, recovery.state.runCount)
    }

    @Test fun exhaustedOrTerminalGoalDoesNotGainAnotherRun() {
        val blocked = runningGoal().copy(state = GoalState.BLOCKED, modelCalls = 2)
        assertTrue(GoalReducer.reduce(blocked, GoalEvent.RecoveryInspection).effects.none { it is GoalEffect.StartRun })
        listOf(GoalState.COMPLETED, GoalState.FAILED, GoalState.CANCELLED).forEach {
            assertTrue(GoalReducer.reduce(blocked.copy(state = it), GoalEvent.RecoveryInspection).ignored)
        }
    }
}
