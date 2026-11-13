package com.helix.app.application.agent

import com.helix.core.agent.Goal
import com.helix.core.agent.RunControlConfig
import com.helix.core.model.AgentMode
import com.helix.core.model.CorrelationId
import com.helix.core.model.GoalBudgets
import com.helix.core.model.GoalId
import com.helix.core.model.GoalState
import com.helix.core.model.TurnBudgets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalContinuationDriverTest {
    private val control = RunControlConfig(AgentMode.GOAL, true, TurnBudgets(maxOutputTokens = 100))
    private val allowed =
        GoalContinuationFacts(
            Goal
                .initial(GoalId("goal"), "finish", emptyList(), GoalBudgets(5), CorrelationId("goal"))
                .copy(state = GoalState.PAUSED),
            1,
            "COMPLETED",
            null,
            null,
            false,
            false,
            false,
            false,
            true,
            "RUN_FINISHED",
            true,
        )

    @Test fun oneSnapshotBuildsCommandAndFreshReadRevokesIt() {
        var facts = allowed
        var reads = 0
        val driver =
            GoalContinuationDriver { _, _ ->
                reads++
                facts
            }
        driver.started("session", "goal", "provider", control, "turn", null, "snapshot")
        val next = requireNotNull(driver.next("session", "turn"))
        assertEquals(1, reads)
        assertTrue(requireNotNull(next.text).startsWith("[Goal continuation 2/5]"))
        facts = facts.copy(hasUnsettledCalls = true)
        assertFalse(driver.admits("session", "goal", requireNotNull(next.goalContinuation), "snapshot"))
        assertNull(driver.next("session", "turn"))
        assertFalse(driver.isArmed("session", "goal"))
    }

    @Test fun durableFactsDoNotRestoreActivationAndWrongPredecessorDoesNotRead() {
        val driver = GoalContinuationDriver { _, _ -> error("No read without a live matching activation") }
        assertNull(driver.next("session", "turn"))
        driver.started("session", "goal", "provider", control, "turn", null, "snapshot")
        assertNull(driver.next("session", "older"))
        driver.disarmAll()
        assertNull(driver.resumeEligible("session"))
    }

    @Test fun settledContinuationRejectsEveryDurableBarrier() {
        val rejected =
            listOf(
                allowed.copy(roundsUsed = 5),
                allowed.copy(turnState = "CANCELLED"),
                allowed.copy(turnErrorCode = "FAILURE"),
                allowed.copy(pauseRequestedAt = 1),
                allowed.copy(predecessorRunFinished = false),
                allowed.copy(predecessorRunOutcome = "MODEL_COMPLETED"),
                allowed.copy(predecessorIsLatestRun = false),
                allowed.copy(hasUnsettledCalls = true),
                allowed.copy(hasDeniedCalls = true),
                allowed.copy(goal = allowed.goal.copy(state = GoalState.BLOCKED)),
            )
        rejected.forEach { facts ->
            val driver = GoalContinuationDriver { _, _ -> facts }
            driver.started("session", "goal", "provider", control, "turn", null, "snapshot")
            assertNull(driver.next("session", "turn"))
            assertFalse(driver.hasActivation("session"))
        }
    }

    @Test fun preparedGoalNeedsNoPredecessorRunButMustBeSettled() {
        var facts = allowed.copy(predecessorRunFinished = false, predecessorIsLatestRun = false)
        val driver = GoalContinuationDriver { _, _ -> facts }
        driver.prepare("session", "goal", "provider", control, "turn", "snapshot")
        assertNotNull(driver.next("session", "turn"))
        listOf(facts.copy(hasPendingEdit = true), facts.copy(hasOpenRuns = true)).forEach { blocked ->
            facts = blocked
            driver.prepare("session", "goal", "provider", control, "turn", "snapshot")
            assertNull(driver.next("session", "turn"))
        }
    }
}
