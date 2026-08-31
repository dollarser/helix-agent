package com.helix.core.agent

import com.helix.core.model.ArtifactRef
import com.helix.core.model.GoalState
import com.helix.core.model.PlanArtifact
import com.helix.core.model.PlanId
import com.helix.core.model.PlanStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalReducerLifecycleTest {
    @Test
    fun newGoalStartsAsDraft() {
        val goal = GoalFixtures.newGoal()
        assertEquals(GoalState.DRAFT, goal.state)
        assertEquals(0, goal.runCount)
    }

    @Test
    fun readyMovesDraftToReady() {
        val goal = GoalFixtures.newGoal()
        val step = reduceGoal(goal, GoalEvent.Ready(null, null))
        assertEquals(GoalState.READY, step.state.state)
        assertTrue(step.effects.isEmpty())
    }

    @Test
    fun readyAttachesPlanIdAndHash() {
        val planId = PlanId("plan-1")
        val plan =
            PlanArtifact(
                id = planId,
                objective = "Investigate the login flow",
                assumptions = emptyList(),
                steps = listOf(PlanStep("One", "Do it")),
                acceptanceCriteria = listOf("Works"),
                risks = emptyList(),
                version = 1,
            )
        val step = reduceGoal(GoalFixtures.newGoal(), GoalEvent.Ready(planId, plan.sha256()))
        assertEquals(planId, step.state.planId)
        assertEquals(plan.sha256(), step.state.planHash)
    }

    @Test
    fun readyPlanMismatchIsRejected() {
        assertThrows<IllegalArgumentException> { GoalEvent.Ready(PlanId("plan-1"), null) }
    }

    @Test
    fun readyIsIgnoredOutsideDraft() {
        val step = GoalReducer.reduce(runningGoal(), GoalEvent.Ready(null, null))
        assertTrue(step.ignored)
    }

    @Test
    fun continueStartsRunWithRemainingBudget() {
        val ready = reduceGoal(GoalFixtures.newGoal(), GoalEvent.Ready(null, null)).state
        val step = reduceGoal(ready, GoalEvent.Continued(GoalWakeReason.USER_OPEN))
        assertEquals(GoalState.RUNNING, step.state.state)
        assertEquals(1, step.state.runCount)
        assertEquals(GoalWakeReason.USER_OPEN, step.state.lastWakeReason)
        val start = step.effects.single() as GoalEffect.StartRun
        assertEquals(GoalWakeReason.USER_OPEN, start.wakeReason)
        assertEquals(1, start.runNumber)
        assertEquals(2, start.remainingModelCalls)
        assertEquals(5, start.remainingToolCalls)
        assertEquals(100, start.remainingTotalTokens)
        assertNull(start.planHash)
    }

    @Test
    fun continueFromPausedAndInputRequired() {
        // Park the goal via budget exhaustion, extend the budget, then continue.
        var goal = runningGoal()
        goal = reduceGoal(goal, GoalEvent.WakeUsageReported(3, 0, 0, 0)).state
        assertEquals(GoalState.PAUSED, goal.state)
        goal = reduceGoal(goal, GoalEvent.BudgetsUpdated(GoalFixtures.budgets(maxModelCalls = 5))).state
        val fromPaused = reduceGoal(goal, GoalEvent.Continued(GoalWakeReason.NOTIFICATION_ACTION))
        assertEquals(GoalState.RUNNING, fromPaused.state.state)
        assertEquals(GoalWakeReason.NOTIFICATION_ACTION, fromPaused.state.lastWakeReason)

        var parked = runningGoal()
        parked = reduceGoal(parked, GoalEvent.InputRequired("permission revoked")).state
        val fromInput = reduceGoal(parked, GoalEvent.Continued(GoalWakeReason.USER_OPEN))
        assertEquals(GoalState.RUNNING, fromInput.state.state)
    }

    @Test
    fun continueIsIgnoredFromTerminalAndDraft() {
        val terminal =
            listOf(
                GoalState.DRAFT to GoalFixtures.newGoal(),
                GoalState.COMPLETED to reduceGoal(fullySatisfiedRunningGoal(), GoalEvent.CompleteRequested).state,
                GoalState.FAILED to reduceGoal(runningGoal(), GoalEvent.WakeFailed(GoalFixtures.error())).state,
                GoalState.CANCELLED to reduceGoal(runningGoal(), GoalEvent.Cancelled).state,
            )
        for ((expectedFrom, goal) in terminal) {
            val step = GoalReducer.reduce(goal, GoalEvent.Continued(GoalWakeReason.USER_OPEN))
            assertTrue("continue from $expectedFrom must be ignored", step.ignored)
        }
    }

    @Test
    fun cancelFromAnyNonTerminalState() {
        val states =
            listOf(
                GoalState.DRAFT to GoalFixtures.newGoal(),
                GoalState.READY to reduceGoal(GoalFixtures.newGoal(), GoalEvent.Ready(null, null)).state,
                GoalState.RUNNING to runningGoal(),
                GoalState.PAUSED to GoalReducer.afterProcessDeath(runningGoal()),
                GoalState.INPUT_REQUIRED to reduceGoal(runningGoal(), GoalEvent.InputRequired("r")).state,
            )
        for ((expectedFrom, goal) in states) {
            val step = GoalReducer.reduce(goal, GoalEvent.Cancelled)
            assertTrue("cancel from $expectedFrom must not be ignored", !step.ignored)
            assertEquals(GoalState.CANCELLED, step.state.state)
            assertEquals("cancelled", step.state.finishReason)
        }
    }

    @Test
    fun cancelIsIgnoredFromTerminal() {
        val cancelled = reduceGoal(runningGoal(), GoalEvent.Cancelled).state
        val step = GoalReducer.reduce(cancelled, GoalEvent.Cancelled)
        assertTrue(step.ignored)
    }

    @Test
    fun processDeathParksRunningGoal() {
        val running = runningGoal()
        val dead = GoalReducer.afterProcessDeath(running)
        assertEquals(GoalState.PAUSED, dead.state)
        assertEquals(0L, dead.currentWakeMillis)
    }

    @Test
    fun processDeathLeavesOtherStatesUnchanged() {
        val goals =
            listOf(
                GoalFixtures.newGoal(),
                reduceGoal(GoalFixtures.newGoal(), GoalEvent.Ready(null, null)).state,
                reduceGoal(runningGoal(), GoalEvent.InputRequired("r")).state,
                reduceGoal(runningGoal(), GoalEvent.Cancelled).state,
            )
        for (goal in goals) {
            assertEquals(goal, GoalReducer.afterProcessDeath(goal))
        }
    }

    @Test
    fun processDeathKeepsCheckpointForWake() {
        var goal = runningGoal()
        val checkpoint = Checkpoint(1_000_000L)
        goal = reduceGoal(goal, GoalEvent.CheckpointScheduled(checkpoint)).state
        val dead = GoalReducer.afterProcessDeath(goal)
        assertEquals(GoalState.PAUSED, dead.state)
        assertEquals(checkpoint, dead.nextCheckpoint)
    }

    @Test
    fun runFinishedParksGoalAwaitingNextWake() {
        val goal = runningGoal()
        val step = reduceGoal(goal, GoalEvent.RunFinished)
        assertEquals(GoalState.PAUSED, step.state.state)
        assertEquals(0L, step.state.currentWakeMillis)
        assertEquals(listOf(GoalEffect.RunFinished), step.effects)
    }

    @Test
    fun runFinishedReschedulesPendingCheckpointReminder() {
        var goal = runningGoal()
        val checkpoint = Checkpoint(99_000L)
        goal = reduceGoal(goal, GoalEvent.CheckpointScheduled(checkpoint)).state
        val step = reduceGoal(goal, GoalEvent.RunFinished)
        assertEquals(GoalState.PAUSED, step.state.state)
        assertEquals(listOf(GoalEffect.RunFinished, GoalEffect.ScheduleCheckpointReminder(checkpoint)), step.effects)
    }

    @Test
    fun runFinishedIsIgnoredOutsideRunning() {
        val ready = reduceGoal(GoalFixtures.newGoal(), GoalEvent.Ready(null, null)).state
        assertTrue(GoalReducer.reduce(ready, GoalEvent.RunFinished).ignored)
    }

    /** A RUNNING goal whose single criterion already carries verifier evidence. */
    private fun fullySatisfiedRunningGoal(): Goal {
        val goal = runningGoal()
        val evidence =
            CriterionEvidence(verifier = "login-verifier", artifactRef = ArtifactRef("artifact-1"), toolCallId = null)
        return reduceGoal(goal, GoalEvent.CriterionSatisfied("c1", evidence)).state
    }
}
