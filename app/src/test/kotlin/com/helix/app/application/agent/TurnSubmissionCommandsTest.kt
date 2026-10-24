package com.helix.app.application.agent

import com.helix.core.agent.GoalBudgetDefaults
import com.helix.core.agent.SubmitTurnCommand
import com.helix.core.model.AgentMode
import com.helix.core.model.ProviderId
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.SessionId
import com.helix.core.model.TurnBudgets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TurnSubmissionCommandsTest {
    private val command =
        SubmitTurnCommand(
            session = SessionId("explicit-session"),
            providerId = ProviderId("provider"),
            mode = AgentMode.PLAN,
            text = "synthetic input",
            budgets = TurnBudgets(maxOutputTokens = 2048),
            reasoning = ReasoningEffort.MEDIUM,
            clientRequestId = "original-request",
        )

    @Test fun producerControlFactsRemainExplicitAndUseGoalDefaultOnlyWhenAbsent() {
        val control = command.runControl()
        assertEquals(AgentMode.PLAN, control.mode)
        assertEquals(command.budgets, control.budgets)
        assertEquals(ReasoningEffort.MEDIUM, control.reasoning)
        assertEquals(GoalBudgetDefaults.VALUE, control.goalBudgets)
        val limited = GoalBudgetDefaults.VALUE.copy(maxGoalRounds = 3)
        assertEquals(limited, command.copy(goalBudgets = limited).runControl().goalBudgets)
    }

    @Test fun futureImmediateMessageDefaultDoesNotRetargetAcceptedConfiguration() {
        val control = command.runControl()
        assertEquals(
            SessionInputConfiguration.hash("provider-facts", control, "PLAN"),
            SessionInputConfiguration.hash("provider-facts", control.copy(immediateMessages = true), "PLAN"),
        )
    }

    @Test fun providerModeAndExecutionBudgetChangesInvalidateAcceptedConfiguration() {
        val control = command.runControl()
        val original = SessionInputConfiguration.hash("provider-facts", control, "PLAN")
        assertNotEquals(original, SessionInputConfiguration.hash("different-provider", control, "PLAN"))
        assertNotEquals(original, SessionInputConfiguration.hash("provider-facts", control, "ACT"))
        assertNotEquals(
            original,
            SessionInputConfiguration.hash("provider-facts", control.copy(budgets = TurnBudgets(4096)), "PLAN"),
        )
    }

    @Test fun refusalNeverMasqueradesAsStartedReceipt() {
        assertNull(TurnSubmissionResult.Blocked(TurnSubmissionBlock.RECEIPT_CONFLICT).turnIdOrNull())
        assertEquals("original-turn", TurnSubmissionResult.Started("original-turn", deduplicated = true).turnIdOrNull())
    }
}
