package com.helix.app.proot

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeSubmissionBudgetTest {
    @Test fun expiredAndSubminimumWindowsNeverGainOneSecond() {
        assertEquals(0L, RuntimeSubmissionBudget(1_000, 1_001, 100).remainingMillis(100))
        assertEquals(0L, RuntimeSubmissionBudget(1_999, 1_000, 100).remainingMillis(100))
        val budget = RuntimeSubmissionBudget(3_000, 1_000, 100)
        assertEquals(1_000L, budget.remainingMillis(1_100))
        assertEquals(0L, budget.remainingMillis(1_101))
    }

    @Test fun preparationSpendsTheWindowAndMonotonicRollbackFailsClosed() {
        val budget = RuntimeSubmissionBudget(61_000, 1_000, 100)
        assertEquals(55_000L, budget.remainingMillis(5_100))
        assertEquals(0L, budget.remainingMillis(60_100))
        assertEquals(0L, budget.remainingMillis(99))
        assertEquals(0L, budget.remainingMillis(Long.MAX_VALUE))
    }

    @Test fun oversizedCallerDeadlineIsCappedWithoutOverflow() {
        val budget = RuntimeSubmissionBudget(Long.MAX_VALUE, 0, 0)
        assertEquals(3_600_000L, budget.remainingMillis(0))
        assertEquals(3_599_000L, budget.remainingMillis(1_000))
    }
}
