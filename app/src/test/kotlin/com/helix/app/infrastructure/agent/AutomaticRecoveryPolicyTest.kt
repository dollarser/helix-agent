package com.helix.app.infrastructure.agent

import com.helix.app.infrastructure.agent.AutomaticRecoveryPolicy
import com.helix.core.agent.GoalBudgetDefaults
import com.helix.core.agent.RunControlConfig
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

    @Test fun inspectionPreservesOutputSettingAndIgnoresHistoricalResourceUsage() {
        val snapshot = snapshot().copy(consumedModelCalls = 12, consumedTokens = 2000, admittedToolRounds = 20)
        run {
            val config = requireNotNull(AutomaticRecoveryPolicy.limits(snapshot))
            assertEquals(snapshot.control.budgets, config)
            assertEquals(200L, config.maxOutputTokens)
        }
    }

    @Test fun parentAndFailedInspectionHaveOneNoticeOwnerAfterRecreation() {
        val child =
            parent.copy(
                id = "child",
                state = "FAILED",
                recoveryFromTurnId = parent.id,
                clientRequestId = AutomaticRecoveryPolicy.requestId(parent.id),
            )
        val rows = mapOf(parent.id to parent, child.id to child)
        val owners =
            listOf(parent, child, child.copy()).map {
                AutomaticRecoveryPolicy.noticeOwner(it) { id -> rows.getValue(id) }.id
            }
        assertEquals(listOf("parent", "parent", "parent"), owners)
    }

    @Test(expected = IllegalArgumentException::class)
    fun noticeCannotBeRetargetedToAnotherSession() {
        val child =
            parent.copy(
                id = "child",
                recoveryFromTurnId = parent.id,
                clientRequestId = AutomaticRecoveryPolicy.requestId(parent.id),
            )
        AutomaticRecoveryPolicy.noticeOwner(child) { parent.copy(sessionId = "other") }
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
                TurnBudgets(maxOutputTokens = 200),
                ReasoningEffort.OFF,
                GoalBudgetDefaults.VALUE,
            ),
            null,
            0,
            0,
            0,
        )
}
