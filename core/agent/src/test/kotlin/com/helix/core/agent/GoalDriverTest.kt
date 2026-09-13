package com.helix.core.agent

import com.helix.core.model.GoalBudgets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the [GoalDriver] pre-flight admission layer (HX2-08).
 *
 * The invariant under test: the driver is a strict pre-filter of the reducer — anything it
 * admits, [GoalReducer] accepts as a [GoalEvent.Continued]; anything it rejects, the reducer
 * would ignore or cannot accept. The driver's *source* policy (which wake types may attempt a
 * run at all) is the one decision the reducer does not make.
 */
class GoalDriverTest {
    // --- goal-state builders (kept out of GoalFixtures to avoid top-level name clashes) ---

    private fun readyGoal(budgets: GoalBudgets = GoalFixtures.budgets()): Goal =
        reduceGoal(GoalFixtures.newGoal(budgets), GoalEvent.Ready(null, null)).state

    private fun pausedGoal(budgets: GoalBudgets = GoalFixtures.budgets()): Goal {
        val running = reduceGoal(readyGoal(budgets), GoalEvent.Continued(GoalWakeReason.USER_OPEN)).state
        return reduceGoal(running, GoalEvent.RunFinished).state
    }

    private fun inputRequiredGoal(): Goal = reduceGoal(runningGoal(), GoalEvent.InputRequired("a password")).state

    private fun blockedGoal(): Goal = reduceGoal(runningGoal(), GoalEvent.Blocked).state

    private fun completedGoal(): Goal = reduceGoal(runningGoal(), GoalEvent.CompleteRequested).state

    private fun failedGoal(): Goal =
        reduceGoal(runningGoal(), GoalEvent.WakeFailed(GoalFixtures.error(retryable = false))).state

    private fun cancelledGoal(): Goal = reduceGoal(readyGoal(), GoalEvent.Cancelled).state

    private fun reject(admission: GoalRunAdmission): GoalRejectionReason {
        assertTrue("expected a Rejected admission, got $admission", admission is GoalRunAdmission.Rejected)
        return (admission as GoalRunAdmission.Rejected).reason
    }

    private val resumableGoals: List<Goal>
        get() = listOf(readyGoal(), pausedGoal(), inputRequiredGoal())

    private val permittedSources: List<GoalWakeReason>
        get() = listOf(GoalWakeReason.USER_OPEN, GoalWakeReason.NOTIFICATION_ACTION)

    // --- admission happy path ---

    @Test
    fun eachResumableStateAdmitsEveryPermittedSource() {
        for (goal in resumableGoals) {
            for (source in permittedSources) {
                assertEquals(GoalRunAdmission.Admitted(source), GoalDriver.admit(goal, source))
            }
        }
    }

    @Test
    fun anAdmittedWakeIsAlwaysAcceptedByTheReducer() {
        for (goal in resumableGoals) {
            for (source in permittedSources) {
                val admission = GoalDriver.admit(goal, source)
                assertTrue(admission is GoalRunAdmission.Admitted)
                assertTrue(
                    "reducer must accept the wake the driver admitted (${goal.state}/$source)",
                    !GoalReducer.reduce(goal, GoalEvent.Continued(source)).ignored,
                )
            }
        }
    }

    // --- state gate (mirrors the reducer's Continued acceptance) ---

    @Test
    fun aNonResumableStateIsRejectedForAPermittedSource() {
        // DRAFT, RUNNING and BLOCKED cannot accept a Continued, for any permitted source.
        for (goal in listOf(GoalFixtures.newGoal(), runningGoal(), blockedGoal())) {
            val admission = GoalDriver.admit(goal, GoalWakeReason.USER_OPEN)
            assertEquals(GoalRejectionReason.STATE_NOT_ADMITTABLE, reject(admission))
        }
    }

    // --- budget gate (shares Goal.hasRunBudgetHeadroom with the reducer) ---

    @Test
    fun eachExhaustedBudgetDimensionIsRejected() {
        val paused = pausedGoal()
        val noModelCalls = paused.copy(modelCalls = paused.budgets.maxModelCalls)
        val noToolCalls = paused.copy(toolCalls = paused.budgets.maxToolCalls)
        val noTokens = paused.copy(totalTokens = paused.budgets.maxTotalTokens)
        val noDuration = paused.copy(runTimeMillis = paused.budgets.maxDurationMillis)
        for (goal in listOf(noModelCalls, noToolCalls, noTokens, noDuration)) {
            assertEquals(GoalRejectionReason.BUDGET_EXHAUSTED, reject(GoalDriver.admit(goal, GoalWakeReason.USER_OPEN)))
        }
    }

    // --- terminal gate ---

    @Test
    fun aTerminalGoalIsRejectedForAPermittedSource() {
        for (goal in listOf(completedGoal(), failedGoal(), cancelledGoal())) {
            assertEquals(GoalRejectionReason.GOAL_TERMINAL, reject(GoalDriver.admit(goal, GoalWakeReason.USER_OPEN)))
        }
    }

    // --- source policy (the driver's own decision, above the reducer) ---

    @Test
    fun theV1PolicyGatesEveryAmbientSourceFromEveryResumableState() {
        val ambient =
            listOf(
                GoalWakeReason.FOREGROUND_CONTINUATION,
                GoalWakeReason.SCHEDULED_CHECKPOINT,
                GoalWakeReason.CHANNEL_EVENT,
            )
        for (goal in resumableGoals) {
            for (source in ambient) {
                assertEquals(GoalRejectionReason.SOURCE_NOT_PERMITTED, reject(GoalDriver.admit(goal, source)))
            }
        }
    }

    @Test
    fun aGatedSourceIsReportedRegardlessOfGoalState() {
        // The source rejection is a policy decision, reported before (and independent of) the
        // goal's own resumability: a RUNNING goal (itself not resumable) still reports
        // SOURCE_NOT_PERMITTED first for a gated source.
        assertEquals(
            GoalRejectionReason.SOURCE_NOT_PERMITTED,
            reject(GoalDriver.admit(runningGoal(), GoalWakeReason.SCHEDULED_CHECKPOINT)),
        )
    }

    @Test
    fun terminalIsReportedBeforeSourcePolicy() {
        assertEquals(
            GoalRejectionReason.GOAL_TERMINAL,
            reject(GoalDriver.admit(completedGoal(), GoalWakeReason.SCHEDULED_CHECKPOINT)),
        )
    }

    @Test
    fun aPolicyCanUnlockAnAmbientSource() {
        val unlocked = GoalWakePolicy(setOf(GoalWakeReason.SCHEDULED_CHECKPOINT))
        assertEquals(
            GoalRunAdmission.Admitted(GoalWakeReason.SCHEDULED_CHECKPOINT),
            GoalDriver.admit(pausedGoal(), GoalWakeReason.SCHEDULED_CHECKPOINT, unlocked),
        )
    }

    @Test
    fun anEmptyPolicyAdmitsNothing() {
        assertEquals(
            GoalRejectionReason.SOURCE_NOT_PERMITTED,
            reject(GoalDriver.admit(pausedGoal(), GoalWakeReason.USER_OPEN, GoalWakePolicy(emptySet()))),
        )
    }
}
