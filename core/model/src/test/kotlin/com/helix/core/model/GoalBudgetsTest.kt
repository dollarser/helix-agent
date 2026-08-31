package com.helix.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class GoalBudgetsTest {
    private fun budgets(): GoalBudgets =
        GoalBudgets(
            maxModelCalls = 10,
            maxToolCalls = 20,
            maxTotalTokens = 100_000,
            maxDurationMillis = 3_600_000,
            maxWakeDurationMillis = 600_000,
            maxRetries = 3,
        )

    @Test
    fun storageStringMatchesKnownVector() {
        val expected =
            "{\"maxModelCalls\":10,\"maxToolCalls\":20,\"maxTotalTokens\":100000," +
                "\"maxDurationMillis\":3600000,\"maxWakeDurationMillis\":600000,\"maxRetries\":3}"
        assertEquals(expected, budgets().toStorageString())
    }

    @Test
    fun roundTripPreservesValues() {
        val parsed = GoalBudgets.parse(budgets().toStorageString())
        assertEquals(budgets(), parsed)
    }

    @Test
    fun stricterWithTakesElementWiseMin() {
        val other =
            GoalBudgets(
                maxModelCalls = 5,
                maxToolCalls = 20,
                maxTotalTokens = 100_000,
                maxDurationMillis = 1_800_000,
                maxWakeDurationMillis = 999_999,
                maxRetries = 3,
            )
        val stricter = budgets().stricterWith(other)
        assertEquals(5, stricter.maxModelCalls)
        assertEquals(20, stricter.maxToolCalls)
        assertEquals(1_800_000, stricter.maxDurationMillis)
    }

    @Test
    fun rejectsInvalidBudgets() {
        assertThrows<IllegalArgumentException> { budgets().copy(maxModelCalls = 0) }
        assertThrows<IllegalArgumentException> { budgets().copy(maxToolCalls = -1) }
        assertThrows<IllegalArgumentException> { budgets().copy(maxTotalTokens = -1) }
        assertThrows<IllegalArgumentException> { budgets().copy(maxDurationMillis = -1) }
        assertThrows<IllegalArgumentException> { budgets().copy(maxWakeDurationMillis = -1) }
        assertThrows<IllegalArgumentException> { budgets().copy(maxRetries = -1) }
    }

    @Test
    fun zeroDurationsAndRetriesAreLegal() {
        val degenerate =
            budgets().copy(
                maxTotalTokens = 0,
                maxDurationMillis = 0,
                maxWakeDurationMillis = 0,
                maxRetries = 0,
            )
        assertEquals(0, degenerate.maxRetries)
    }
}
