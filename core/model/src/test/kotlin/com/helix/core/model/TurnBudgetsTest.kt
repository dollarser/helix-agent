package com.helix.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class TurnBudgetsTest {
    private val budgets =
        TurnBudgets(
            maxSteps = 8,
            maxModelCalls = 3,
            maxInputTokens = 12_000L,
            maxOutputTokens = 2_000L,
            maxTotalTokens = 14_000L,
        )

    @Test
    fun rejectsNonPositiveStepAndCallLimits() {
        assertThrows<IllegalArgumentException> { TurnBudgets(0, 1, 1, 1, 1) }
        assertThrows<IllegalArgumentException> { TurnBudgets(1, 0, 1, 1, 1) }
        assertThrows<IllegalArgumentException> { TurnBudgets(-1, 1, 1, 1, 1) }
    }

    @Test
    fun rejectsNegativeTokenLimits() {
        assertThrows<IllegalArgumentException> { TurnBudgets(1, 1, -1, 0, 0) }
        assertThrows<IllegalArgumentException> { TurnBudgets(1, 1, 0, -1, 0) }
        assertThrows<IllegalArgumentException> { TurnBudgets(1, 1, 0, 0, -1) }
    }

    @Test
    fun allowsZeroTokenLimits() {
        val zero = TurnBudgets(1, 1, 0, 0, 0)
        assertEquals(0L, zero.maxInputTokens)
    }

    @Test
    fun stricterWithTakesElementWiseMinimum() {
        val other = TurnBudgets(4, 5, 9_000, 5_000, 9_000)
        val stricter = budgets.stricterWith(other)
        assertEquals(4, stricter.maxSteps)
        assertEquals(3, stricter.maxModelCalls)
        assertEquals(9_000L, stricter.maxInputTokens)
        assertEquals(2_000L, stricter.maxOutputTokens)
        assertEquals(9_000L, stricter.maxTotalTokens)
    }

    @Test
    fun storageEncodingIsStableAndRoundTrips() {
        val encoded = budgets.toStorageString()
        assertEquals(
            """{"maxSteps":8,"maxModelCalls":3,"maxInputTokens":12000,"maxOutputTokens":2000,"maxTotalTokens":14000}""",
            encoded,
        )
        assertEquals(budgets, TurnBudgets.parse(encoded))
        // Deterministic: encoding the same value twice is byte-identical.
        assertEquals(encoded, budgets.toStorageString())
    }

    @Test
    fun parseRejectsMalformedInput() {
        val valid = budgets.toStorageString()
        assertThrows<IllegalArgumentException> { TurnBudgets.parse("") }
        assertThrows<IllegalArgumentException> { TurnBudgets.parse("not json") }
        // Missing a field.
        assertThrows<IllegalArgumentException> {
            TurnBudgets.parse("""{"maxSteps":8,"maxModelCalls":3,"maxInputTokens":1,"maxOutputTokens":1}""")
        }
        // Unknown field.
        assertThrows<IllegalArgumentException> { TurnBudgets.parse(valid.replace("}", ",\"bogus\":1}")) }
        // Wrong type.
        assertThrows<IllegalArgumentException> { TurnBudgets.parse(valid.replace("12000", "\"12000\"")) }
        // Float where a number is expected.
        assertThrows<IllegalArgumentException> { TurnBudgets.parse(valid.replace("8", "8.0")) }
        // Out of int range for a step limit.
        assertThrows<IllegalArgumentException> { TurnBudgets.parse(valid.replace("8", "99999999999")) }
    }
}
