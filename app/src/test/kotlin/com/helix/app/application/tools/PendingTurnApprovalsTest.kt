package com.helix.app.application.tools

import com.helix.app.application.tools.PendingTurnApprovals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PendingTurnApprovalsTest {
    @Test fun cancellingOrFinishingOneTurnPreservesOtherSessionsAndAllTheirCards() {
        val approvals = PendingTurnApprovals()
        approvals.register("a1", "turn-a", "call-a1")
        approvals.register("b1", "turn-b", "call-b1")
        approvals.register("b2", "turn-b", "call-b2")
        assertEquals(listOf("a1"), approvals.forTurn("turn-a"))
        approvals.finishTurn("turn-a")
        assertEquals(emptyList<String>(), approvals.forTurn("turn-a"))
        assertEquals(setOf("b1", "b2"), approvals.forTurn("turn-b").toSet())
    }

    @Test fun wrongTurnAndRepeatedClaimCannotTakeAnOriginalRequest() {
        val approvals = PendingTurnApprovals()
        approvals.register("request", "turn-a", "call-a")
        assertNull(approvals.claim("turn-b", "request"))
        assertEquals(PendingTurnApprovals.Identity("turn-a", "call-a"), approvals.claim("turn-a", "request"))
        assertNull(approvals.claim("turn-a", "request"))
        assertEquals(listOf("request"), approvals.forTurn("turn-a"))
        approvals.remove("request")
        assertEquals(emptyList<String>(), approvals.forTurn("turn-a"))
    }

    @Test fun conflictingIdentityCannotReplaceTheOriginalBinding() {
        val approvals = PendingTurnApprovals()
        approvals.register("request", "turn-a", "call-a")
        assertThrows(IllegalStateException::class.java) { approvals.register("request", "turn-b", "call-b") }
        assertEquals(PendingTurnApprovals.Identity("turn-a", "call-a"), approvals.find("request"))
    }

    @Test fun competingTapsClaimTheOriginalRequestOnlyOnce() {
        val approvals = PendingTurnApprovals()
        approvals.register("request", "turn-a", "call-a")
        val ready = java.util.concurrent.CountDownLatch(1)
        val winners =
            java.util.concurrent.atomic
                .AtomicInteger()
        val threads =
            (1..8).map {
                Thread {
                    ready.await()
                    if (approvals.claim("turn-a", "request") != null) winners.incrementAndGet()
                }.apply { start() }
            }
        ready.countDown()
        threads.forEach {
            it.join(1000)
            assertFalse(it.isAlive)
        }
        assertEquals(1, winners.get())
    }
}
