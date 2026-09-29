package com.helix.app.agent

import com.helix.core.agent.GoalEvent
import com.helix.core.model.CorrelationId
import com.helix.core.model.TurnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalRunSettlementTest {
    @Test fun persistentNoProgressEndsWithoutRequiringUserRepair() {
        val (event, outcome) =
            GoalRunSettlement.decision(
                CorrelationId("goal"),
                TurnState.FAILED,
                "TOOL_LOOP_NO_PROGRESS",
                false,
            )
        assertTrue(event is GoalEvent.WakeFailed)
        assertEquals("FAILED(TOOL_LOOP_NO_PROGRESS)", outcome)
    }

    @Test fun uncertainEffectsTakePrecedenceOverNoProgress() {
        val (event, outcome) =
            GoalRunSettlement.decision(
                CorrelationId("goal"),
                TurnState.FAILED,
                "TOOL_LOOP_NO_PROGRESS",
                true,
            )
        assertEquals(GoalEvent.Blocked, event)
        assertEquals("BLOCKED(NEEDS_REVIEW)", outcome)
    }
}
