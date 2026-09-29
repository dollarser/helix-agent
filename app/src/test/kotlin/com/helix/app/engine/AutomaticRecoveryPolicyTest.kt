package com.helix.app.engine

import com.helix.app.runcontrol.GoalBudgetDefaults
import com.helix.app.runcontrol.RunControlConfig
import com.helix.core.model.AgentMode
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.TurnBudgets
import com.helix.core.storage.entity.TurnEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticRecoveryPolicyTest {
    private val parent = TurnEntity("parent", "session", "INTERRUPTED", 0, 1, 2, null)

    @Test fun stoppedAndLiveTurnsNeverStartInspection() {
        assertTrue(AutomaticRecoveryPolicy.eligible(parent))
        assertFalse(AutomaticRecoveryPolicy.eligible(parent.copy(errorCode = "USER_STOP")))
        assertFalse(AutomaticRecoveryPolicy.eligible(parent.copy(pauseRequestedAt = 1)))
        assertFalse(AutomaticRecoveryPolicy.eligible(parent.copy(state = "WAITING_MODEL")))
        assertFalse(AutomaticRecoveryPolicy.eligible(parent.copy(state = "CANCELLED")))
    }

    @Test fun inspectionCannotRecursivelyRecover() {
        val child =
            parent.copy(
                id = "child",
                recoveryFromTurnId = parent.id,
                clientRequestId = AutomaticRecoveryPolicy.requestId(parent.id),
            )
        assertTrue(AutomaticRecoveryPolicy.isInspection(child))
        assertFalse(AutomaticRecoveryPolicy.eligible(child))
        assertFalse(AutomaticRecoveryPolicy.isInspection(child.copy(clientRequestId = "user-input")))
    }

    @Test fun ordinaryInspectionOnlyUsesOriginalRemainder() {
        val snapshot = snapshot().copy(consumedModelCalls = 8, consumedTokens = 950, admittedToolRounds = 9)
        val budget = requireNotNull(AutomaticRecoveryPolicy.limits(snapshot, false))
        assertEquals(2, budget.maxModelCalls)
        assertEquals(1, budget.maxSteps)
        assertEquals(50L, budget.maxTotalTokens)
        assertEquals(50L, budget.maxOutputTokens)
        assertNull(AutomaticRecoveryPolicy.limits(snapshot.copy(consumedModelCalls = 10), false))
        assertNull(AutomaticRecoveryPolicy.limits(snapshot.copy(consumedTokens = 1000), false))
        assertNull(AutomaticRecoveryPolicy.limits(snapshot.copy(admittedToolRounds = 10), false))
    }

    @Test fun goalInspectionIsBoundedBeforeCumulativeLedgerAdmission() {
        val budget = requireNotNull(AutomaticRecoveryPolicy.limits(snapshot(), true))
        assertEquals(4, budget.maxModelCalls)
        assertEquals(8, budget.maxSteps)
        assertEquals(1000L, budget.maxTotalTokens)
    }

    private fun snapshot() =
        TurnRuntimeSnapshot(
            "parent",
            "provider",
            "model",
            "{}",
            RunControlConfig(
                AgentMode.ACT,
                true,
                TurnBudgets(10, 10, 800, 200, 1000),
                ReasoningEffort.OFF,
                GoalBudgetDefaults.VALUE,
            ),
            null,
            0,
            0,
            0,
        )
}
