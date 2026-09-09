package com.helix.core.agent

import com.helix.core.model.GoalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalBlockedTest {
    @Test fun blockedRequiresResolutionBeforeContinueAndKeepsUsage() {
        val ready = GoalReducer.reduce(GoalFixtures.newGoal(), GoalEvent.Ready(null, null)).state
        val running = GoalReducer.reduce(ready, GoalEvent.Continued(GoalWakeReason.USER_OPEN)).state
        val blocked = GoalReducer.reduce(running, GoalEvent.Blocked).state
        assertEquals(GoalState.BLOCKED, blocked.state)
        assertTrue(GoalReducer.reduce(blocked, GoalEvent.Continued(GoalWakeReason.USER_OPEN)).ignored)
        assertEquals(blocked, GoalReducer.afterProcessDeath(blocked))
        val resolved = GoalReducer.reduce(blocked, GoalEvent.BlockerResolved).state
        assertEquals(GoalState.PAUSED, resolved.state)
        assertEquals(blocked.runCount, resolved.runCount)
        assertEquals(blocked.totalTokens, resolved.totalTokens)
        val continued = GoalReducer.reduce(resolved, GoalEvent.Continued(GoalWakeReason.USER_OPEN)).state
        assertEquals(GoalState.RUNNING, continued.state)
        assertEquals(running.runCount + 1, continued.runCount)
    }

    @Test fun resolutionCannotInventBudgetOrCompletion() {
        val blocked = GoalFixtures.newGoal().copy(state = GoalState.BLOCKED, modelCalls = 2)
        assertTrue(GoalReducer.reduce(blocked, GoalEvent.BlockerResolved).ignored)
        assertTrue(GoalReducer.reduce(blocked, GoalEvent.CompleteRequested).ignored)
        assertEquals(GoalState.CANCELLED, GoalReducer.reduce(blocked, GoalEvent.Cancelled).state.state)
    }
}
