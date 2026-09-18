package com.helix.app.chat

import com.helix.app.ui.RecoveryBlockClass
import com.helix.app.ui.RecoveryFacts
import com.helix.app.ui.RecoveryOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HXA-204 slice 2: the panel builder is a pure function from the gathered turn facts to the
 * rendered panels. A blocked turn gets a panel; the retry admission belongs to the single
 * retry target only; the Goal continue identity travels with the panel; fail-closed on
 * unparseable facts.
 */
class TurnRecoveryPanelTest {
    @Test
    fun failedTurnGetsPanelKeyedByTurnIdWithRetryOperation() {
        val panels = recoveryPanelsFor(listOf(source("t1")), "t1")
        val panel = panels["t1"]
        assertNotNull(panel)
        assertEquals(listOf(RecoveryOperation.RETRY_NEW_CALL), panel!!.summary.operations)
        assertTrue(panel.retryAllowed)
    }

    @Test
    fun completedTurnGetsNoPanel() {
        val panels = recoveryPanelsFor(listOf(source("t1", turnState = "COMPLETED")), null)
        assertTrue(panels.isEmpty())
    }

    @Test
    fun inProgressTurnGetsNoPanel() {
        val panels = recoveryPanelsFor(listOf(source("t1", turnState = "WAITING_MODEL")), null)
        assertTrue(panels.isEmpty())
    }

    @Test
    fun retryAdmissionBelongsToTheSingleTargetOnly() {
        val panels =
            recoveryPanelsFor(
                listOf(
                    source("t1", errorCode = "SERVER_ERROR"),
                    source("t2", errorCode = "AUTH"),
                ),
                "t2",
            )
        assertFalse(panels["t1"]!!.retryAllowed)
        assertTrue(panels["t2"]!!.retryAllowed)
    }

    @Test
    fun noRetryTargetMeansNoPanelClaimsRetry() {
        val panels = recoveryPanelsFor(listOf(source("t1")), null)
        assertFalse(panels["t1"]!!.retryAllowed)
    }

    @Test
    fun aSupersededFailureKeepsItsFactsButLosesTheRetryAdmission() {
        val panels =
            recoveryPanelsFor(listOf(source("t1").copy(supersededByCompleted = true)), "t1")
        val panel = panels["t1"]!!
        // The existing `chat-retry` visibility rule: a later successful result moves the
        // conversation past the failure, so the panel keeps its facts but no retry button.
        assertFalse(panel.retryAllowed)
        assertTrue(RecoveryOperation.RETRY_NEW_CALL in panel.summary.operations)
    }

    @Test
    fun interruptedTurnPanelIsQueryOnlyWithUnknownResult() {
        val panels = recoveryPanelsFor(listOf(source("t1", turnState = "INTERRUPTED")), null)
        val panel = panels["t1"]!!
        assertEquals(RecoveryBlockClass.RESULT_UNKNOWN, panel.summary.blockClass)
        assertEquals(listOf(RecoveryOperation.QUERY_RESULT), panel.summary.operations)
        assertEquals("INTERRUPTED", panel.turnState)
    }

    @Test
    fun cancelledGoalTurnPanelCarriesTheGoalContinueIdentity() {
        val panels =
            recoveryPanelsFor(
                listOf(
                    source(
                        turnId = "t1",
                        turnState = "CANCELLED",
                        goalId = "g1",
                        goalBound = true,
                        goalContinuable = true,
                    ).copy(goalObjective = "Ship it"),
                ),
                null,
            )
        val panel = panels["t1"]!!
        assertEquals(listOf(RecoveryOperation.CONTINUE_GOAL), panel.summary.operations)
        assertEquals("g1", panel.goalId)
        assertEquals("Ship it", panel.goalObjective)
    }

    @Test
    fun budgetEligiblePanelKeepsTheContinuationFact() {
        val panels =
            recoveryPanelsFor(
                listOf(
                    source(
                        "t1",
                        errorCode = "TOKEN_BUDGET_LIMIT",
                        budgetContinuationEligible = true,
                    ),
                ),
                "t1",
            )
        val panel = panels["t1"]!!
        assertEquals(RecoveryBlockClass.BUDGET, panel.summary.blockClass)
        assertTrue(panel.summary.budgetContinuationEligible)
        assertTrue(panel.retryAllowed)
    }

    @Test
    fun corruptedTurnStateFailsClosedToNoPanel() {
        val panels = recoveryPanelsFor(listOf(source("t1", turnState = "NOT_A_STATE")), null)
        assertTrue(panels.isEmpty())
    }

    @Test
    fun duplicateTurnIdsCollapseToOnePanel() {
        val panels =
            recoveryPanelsFor(listOf(source("t1"), source("t1", errorCode = "AUTH")), "t1")
        assertEquals(1, panels.size)
    }
}

private fun source(
    turnId: String,
    turnState: String = "FAILED",
    errorCode: String? = "SERVER_ERROR",
    goalId: String? = null,
    goalBound: Boolean = false,
    goalContinuable: Boolean = false,
    budgetContinuationEligible: Boolean = false,
): TurnRecoverySource =
    TurnRecoverySource(
        turnId = turnId,
        goalId = goalId,
        goalObjective = null,
        facts =
            RecoveryFacts(
                turnState = turnState,
                turnErrorCode = errorCode,
                toolCalls = emptyList(),
                userPaused = false,
                goalBound = goalBound,
                goalState = null,
                goalContinuable = goalContinuable,
                budgetContinuationEligible = budgetContinuationEligible,
                artifactIds = emptyList(),
            ),
    )
