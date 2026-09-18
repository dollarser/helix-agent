package com.helix.runtime.proot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DetachedLeaseTest {
    @Test fun defaultIsFiveMinutesAndBudgetOnlyTightens() {
        val lease = DetachedLease.create("generation", 100, 200, remainingBudgetMs = 900_000)
        assertEquals(300_000, lease.durationMs)
        assertEquals(300_100, lease.deadlineEpochMs)
        assertEquals(1_000, DetachedLease.create("generation", 0, 0, remainingBudgetMs = 1_000).durationMs)
    }

    @Test fun reconnectDoesNotRenewAndNewRuntimeCannotResume() {
        val lease = DetachedLease.create("generation", 100, 200, remainingBudgetMs = 900_000)
        assertEquals(299_000, lease.remainingMs("generation", 1_200))
        assertEquals(0, lease.remainingMs("new-generation", 1_200))
        assertEquals(0, lease.remainingMs("generation", 199))
        assertEquals(0, lease.remainingMs("generation", 300_200))
        assertEquals(0, lease.remainingMs("generation", Long.MAX_VALUE))
    }

    @Test fun exhaustedBudgetAndOversizedRequestsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            DetachedLease.create("g", 0, 0, remainingBudgetMs = 999)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DetachedLease.create("g", 0, 0, requestedMs = 1_800_001, remainingBudgetMs = Long.MAX_VALUE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DetachedLease.create("g", Long.MAX_VALUE, 0, remainingBudgetMs = 1_000)
        }
    }
}
