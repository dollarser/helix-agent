package com.helix.app.chat

import com.helix.app.agent.AutomaticGoalContinuation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticGoalContinuationTest {
    @Test fun localAttemptLimitsAllowRecheckingTheExistingGoalBudget() {
        for (reason in listOf("MODEL_CALL_LIMIT", "TOOL_STEP_LIMIT", "TURN_TOTAL_TOKEN_LIMIT", "TOKEN_BUDGET_LIMIT")) {
            assertTrue(reason, AutomaticGoalContinuation.accepts("FAILED", reason, null))
        }
        assertTrue(AutomaticGoalContinuation.accepts("COMPLETED", null, null))
    }

    @Test fun totalBudgetCapacityNoProgressAndTransportFailuresDoNotCreateUnlimitedSuccessors() {
        for (reason in listOf(
            "GOAL_BUDGET_LIMIT",
            "CONTEXT_WINDOW_LIMIT",
            "OUTPUT_TOKEN_LIMIT",
            "TOOL_LOOP_NO_PROGRESS",
            "NETWORK",
        )) {
            assertFalse(reason, AutomaticGoalContinuation.accepts("FAILED", reason, null))
        }
    }

    @Test fun userStopAndUncertainExecutionsCannotBeRestartedAsBudgetContinuation() {
        for (state in listOf("CANCELLED", "INTERRUPTED", "NEEDS_REVIEW", "RUNNING")) {
            assertFalse(state, AutomaticGoalContinuation.accepts(state, "MODEL_CALL_LIMIT", null))
        }
        assertFalse(AutomaticGoalContinuation.accepts("FAILED", "MODEL_CALL_LIMIT", 1L))
        assertFalse(AutomaticGoalContinuation.accepts("COMPLETED", null, 1L))
    }
}
