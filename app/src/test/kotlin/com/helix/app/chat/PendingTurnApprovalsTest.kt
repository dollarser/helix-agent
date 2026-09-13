package com.helix.app.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class PendingTurnApprovalsTest {
    @Test fun cancellingOrFinishingOneTurnPreservesOtherSessionsAndAllTheirCards() {
        val approvals = PendingTurnApprovals()
        approvals.register("a1", "turn-a")
        approvals.register("b1", "turn-b")
        approvals.register("b2", "turn-b")
        assertEquals(listOf("a1"), approvals.forTurn("turn-a"))
        approvals.finishTurn("turn-a")
        assertEquals(emptyList<String>(), approvals.forTurn("turn-a"))
        assertEquals(setOf("b1", "b2"), approvals.forTurn("turn-b").toSet())
    }
}
