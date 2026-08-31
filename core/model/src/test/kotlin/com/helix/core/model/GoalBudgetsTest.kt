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
    fun parseRejectsMalformedInput() {
        // ADR-0001 gate: a canonical encoding may serve as a recovery source only with
        // known-vector, round-trip AND malformed-input coverage (mirrors TurnBudgetsTest).
        val valid = budgets().toStorageString()
        assertThrows<IllegalArgumentException> { GoalBudgets.parse("") }
        assertThrows<IllegalArgumentException> { GoalBudgets.parse("not json") }
        // Missing a field (the known encoding with maxRetries stripped).
        val missingField =
            """{"maxModelCalls":10,"maxToolCalls":20,"maxTotalTokens":100000,""" +
                """"maxDurationMillis":3600000,"maxWakeDurationMillis":600000}"""
        assertThrows<IllegalArgumentException> { GoalBudgets.parse(missingField) }
        // Unknown field.
        assertThrows<IllegalArgumentException> { GoalBudgets.parse(valid.replace("}", ",\"bogus\":1}")) }
        // Wrong type.
        assertThrows<IllegalArgumentException> { GoalBudgets.parse(valid.replace("3600000", "\"3600000\"")) }
        // Float where a number is expected.
        assertThrows<IllegalArgumentException> { GoalBudgets.parse(valid.replace("100000", "100000.0")) }
        // Out of int range for a call limit.
        assertThrows<IllegalArgumentException> { GoalBudgets.parse(valid.replace("10", "99999999999")) }
        // Truncated input.
        assertThrows<IllegalArgumentException> { GoalBudgets.parse(valid.dropLast(1)) }
        // Below-domain floor (maxModelCalls must be >= 1).
        assertThrows<IllegalArgumentException> { GoalBudgets.parse(valid.replace("10", "0")) }
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
