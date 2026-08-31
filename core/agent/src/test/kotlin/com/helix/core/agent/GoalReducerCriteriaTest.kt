package com.helix.core.agent

import com.helix.core.model.ArtifactRef
import com.helix.core.model.GoalState
import com.helix.core.model.ToolCallId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalReducerCriteriaTest {
    private fun evidence(): CriterionEvidence =
        CriterionEvidence(
            verifier = "login-verifier",
            artifactRef = ArtifactRef("artifact-1"),
            toolCallId = null,
        )

    @Test
    fun criterionSatisfiedOnlyDuringRunning() {
        val goal = runningGoal()
        val step = reduceGoal(goal, GoalEvent.CriterionSatisfied("c1", evidence()))
        assertEquals(
            "login-verifier",
            step.state.criteria
                .single()
                .evidence
                ?.verifier,
        )
        assertTrue(
            step.state.criteria
                .single()
                .isSatisfied,
        )

        val draft = GoalFixtures.newGoal()
        assertTrue(GoalReducer.reduce(draft, GoalEvent.CriterionSatisfied("c1", evidence())).ignored)
    }

    @Test
    fun unknownOrAlreadySatisfiedCriterionIsIgnored() {
        val goal = runningGoal()
        val satisfied = reduceGoal(goal, GoalEvent.CriterionSatisfied("c1", evidence())).state
        assertTrue(GoalReducer.reduce(satisfied, GoalEvent.CriterionSatisfied("c1", evidence())).ignored)
        assertTrue(GoalReducer.reduce(goal, GoalEvent.CriterionSatisfied("nope", evidence())).ignored)
    }

    @Test
    fun completeRequestedRequiresAllCriteriaSatisfied() {
        val running = runningGoal()
        // Single criterion still unsatisfied: ignored.
        val step = GoalReducer.reduce(running, GoalEvent.CompleteRequested)
        assertTrue(step.ignored)
        assertEquals(GoalState.RUNNING, step.state.state)

        val satisfied = reduceGoal(running, GoalEvent.CriterionSatisfied("c1", evidence())).state
        val completed = reduceGoal(satisfied, GoalEvent.CompleteRequested)
        assertEquals(GoalState.COMPLETED, completed.state.state)
        assertEquals("completed", completed.state.finishReason)
        assertTrue(completed.state.unsatisfiedCriteria.isEmpty())
        val effects = completed.effects
        assertTrue(effects.contains(GoalEffect.GoalCompleted))
        assertTrue(effects.contains(GoalEffect.ReminderCancelled))
    }

    @Test
    fun completionIsIgnoredOutsideRunning() {
        val ready = reduceGoal(GoalFixtures.newGoal(), GoalEvent.Ready(null, null)).state
        assertTrue(GoalReducer.reduce(ready, GoalEvent.CompleteRequested).ignored)
    }

    @Test
    fun evidenceRequiresConcreteReference() {
        assertThrows<IllegalArgumentException> {
            CriterionEvidence(verifier = "v", artifactRef = null, toolCallId = null)
        }
        val ok = CriterionEvidence(verifier = "v", artifactRef = null, toolCallId = ToolCallId("call-1"))
        assertEquals(ToolCallId("call-1"), ok.toolCallId)
    }

    @Test
    fun checkpointScheduledOnlyDuringRunning() {
        val goal = runningGoal()
        val checkpoint = Checkpoint(42_000L)
        val step = reduceGoal(goal, GoalEvent.CheckpointScheduled(checkpoint))
        assertEquals(checkpoint, step.state.nextCheckpoint)
        assertEquals(listOf(GoalEffect.ScheduleCheckpointReminder(checkpoint)), step.effects)

        val draft = GoalFixtures.newGoal()
        assertTrue(GoalReducer.reduce(draft, GoalEvent.CheckpointScheduled(checkpoint)).ignored)
    }

    @Test
    fun inputRequiredParksGoalAndCancelsReminder() {
        var goal = runningGoal()
        goal = reduceGoal(goal, GoalEvent.CheckpointScheduled(Checkpoint(1L))).state
        val step = reduceGoal(goal, GoalEvent.InputRequired("target package changed"))
        assertEquals(GoalState.INPUT_REQUIRED, step.state.state)
        assertEquals(0L, step.state.currentWakeMillis)
        // Checkpoint data survives (durable), but the reminder itself is cancelled.
        assertEquals(Checkpoint(1L), step.state.nextCheckpoint)
        assertTrue(step.effects.contains(GoalEffect.ShowInputRequired("target package changed")))
        assertTrue(step.effects.contains(GoalEffect.ReminderCancelled))
    }

    @Test
    fun inputRequiredReasonMustBeBounded() {
        assertThrows<IllegalArgumentException> { GoalEvent.InputRequired(" ") }
        assertThrows<IllegalArgumentException> { GoalEvent.InputRequired("x".repeat(513)) }
    }
}
