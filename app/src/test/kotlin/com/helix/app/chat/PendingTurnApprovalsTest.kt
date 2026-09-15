package com.helix.app.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class PendingTurnApprovalsTest {
    @Test fun cancellingOrFinishingOneTurnPreservesOtherSessionsAndAllTheirCards() {
        val approvals = PendingTurnApprovals()
        approvals.register("a1", "turn-a", "session-a")
        approvals.register("b1", "turn-b", "session-b")
        approvals.register("b2", "turn-b", "session-b")
        assertEquals(listOf("a1"), approvals.forTurn("turn-a"))
        approvals.finishTurn("turn-a")
        assertEquals(emptyList<String>(), approvals.forTurn("turn-a"))
        assertEquals(setOf("b1", "b2"), approvals.forTurn("turn-b").toSet())
    }

    @Test fun sessionScopeListsOnlyThatSessionsTurnsAndIgnoresOthers() {
        val approvals = PendingTurnApprovals()
        approvals.register("a1", "turn-a", "session-a")
        approvals.register("a2", "turn-a2", "session-a")
        approvals.register("b1", "turn-b", "session-b")
        assertEquals(setOf("a1", "a2"), approvals.forSession("session-a").toSet())
        assertEquals(emptyList<String>(), approvals.forSession("session-c"))
        approvals.finishTurn("turn-a")
        assertEquals(listOf("a2"), approvals.forSession("session-a"))
    }
}
