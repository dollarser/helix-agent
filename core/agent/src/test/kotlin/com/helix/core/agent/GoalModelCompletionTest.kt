package com.helix.core.agent

import com.helix.core.model.GoalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalModelCompletionTest {
    @Test fun objectiveOnlyGoalCanComplete() {
        val running = runningGoal().copy(criteria = emptyList())
        assertEquals(GoalState.COMPLETED, GoalReducer.reduce(running, GoalEvent.CompleteRequested).state.state)
    }

    @Test fun modelReportCompletesWithoutBoundCriteria() {
        val running =
            runningGoal().copy(
                criteria = listOf(Criterion("a", "Clear explanation"), Criterion("b", "Useful UI")),
            )
        val step = GoalReducer.reduce(running, GoalEvent.CompleteRequested)
        assertEquals(GoalState.COMPLETED, step.state.state)
        assertEquals(running.criteria, step.state.criteria)
        assertEquals(running.totalTokens, step.state.totalTokens)
        assertTrue(step.effects.contains(GoalEffect.GoalCompleted))
    }

    @Test fun onlyActiveGoalCanConsumeCompletion() {
        for (state in GoalState.entries.filter { it != GoalState.RUNNING }) {
            assertTrue(GoalReducer.reduce(runningGoal().copy(state = state), GoalEvent.CompleteRequested).ignored)
        }
    }

    @Test fun ordinaryRoundEndDoesNotImplySemanticCompletion() {
        assertEquals(GoalState.PAUSED, GoalReducer.reduce(runningGoal(), GoalEvent.RunFinished).state.state)
    }

    @Test fun malformedCriterionStillRejected() {
        assertThrows(IllegalArgumentException::class.java) { Criterion("", "Description") }
        assertThrows(IllegalArgumentException::class.java) { Criterion("ok", " ") }
    }
}
