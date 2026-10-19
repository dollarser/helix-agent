package com.helix.app.scheduled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ScheduledRuntimeBudgetTest {
    @Test fun disclosedDefaultIsEditableBeyondTwoMinutes() {
        assertEquals("120", ScheduledRuntimeBudget.seconds(ScheduledRuntimeBudget.DEFAULT_MILLIS))
        assertEquals(600_000L, ScheduledRuntimeBudget.parseSeconds("600"))
        assertEquals(3_600_125L, ScheduledRuntimeBudget.parseSeconds("3600.125"))
        assertEquals("3600.125", ScheduledRuntimeBudget.seconds(3_600_125))
    }

    @Test fun invalidZeroNegativeNonFiniteAndOverflowBudgetsFail() {
        listOf("", "0", "-1", "NaN", "Infinity", "1e100", "0.0001").forEach { value ->
            assertThrows(Exception::class.java) { ScheduledRuntimeBudget.parseSeconds(value) }
        }
    }

    @Test fun boundsComeOnlyFromPositiveExactMillisecondRepresentation() {
        assertEquals(1L, ScheduledRuntimeBudget.parseSeconds("0.001"))
        assertEquals(Long.MAX_VALUE, ScheduledRuntimeBudget.parseSeconds("9223372036854775.807"))
    }
}
