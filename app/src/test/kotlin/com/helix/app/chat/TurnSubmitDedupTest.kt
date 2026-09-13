package com.helix.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Unit tests for [TurnSubmitDedup] (research doc section 34; HX2-01 §2e): the pure idempotency
 * ledger behind a turn start's client-request dedup — a re-driven submission with the same id
 * returns the already-started turn, the bounded map evicts oldest-first, and a non-positive
 * capacity fails closed.
 */
class TurnSubmitDedupTest {
    @Test
    fun aNewClientRequestIdIsNotYetStarted() {
        val dedup = TurnSubmitDedup()
        assertNull(dedup.alreadyStarted("req-1"))
    }

    @Test
    fun aRecordedClientRequestIdReturnsItsTurn() {
        val dedup = TurnSubmitDedup()
        dedup.record("req-1", "t1")
        assertEquals("t1", dedup.alreadyStarted("req-1"))
    }

    @Test
    fun reRecordingTheSameClientRequestIdOverwritesTheTurn() {
        val dedup = TurnSubmitDedup()
        dedup.record("req-1", "t1")
        dedup.record("req-1", "t2")
        assertEquals("t2", dedup.alreadyStarted("req-1"))
    }

    @Test
    fun reRecordingAnExistingIdDoesNotEvictAnotherEntry() {
        // Only a NEW id that overflows the capacity evicts the oldest; a re-record of an existing
        // id must not drop an unrelated entry.
        val dedup = TurnSubmitDedup(capacity = 2)
        dedup.record("a", "t-a")
        dedup.record("b", "t-b")
        dedup.record("a", "t-a2")
        assertEquals("t-a2", dedup.alreadyStarted("a"))
        assertEquals("t-b", dedup.alreadyStarted("b"))
    }

    @Test
    fun aNewIdBeyondCapacityEvictsTheOldest() {
        val dedup = TurnSubmitDedup(capacity = 2)
        dedup.record("a", "t-a")
        dedup.record("b", "t-b")
        dedup.record("c", "t-c")
        assertNull(dedup.alreadyStarted("a"))
        assertEquals("t-b", dedup.alreadyStarted("b"))
        assertEquals("t-c", dedup.alreadyStarted("c"))
    }

    @Test
    fun aNonPositiveCapacityIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            TurnSubmitDedup(capacity = 0)
        }
    }
}
