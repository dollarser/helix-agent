package com.helix.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class TurnBudgetsTest {
    @Test fun storageEncodingIsStableAndRoundTrips() {
        val value = TurnBudgets(2000)
        assertEquals("""{"maxOutputTokens":2000}""", value.toStorageString())
        assertEquals(value, TurnBudgets.parse(value.toStorageString()))
        assertEquals(value.toStorageString(), value.toStorageString())
    }

    @Test fun stricterAllowanceNeverRaisesEitherInput() {
        assertEquals(TurnBudgets(3), TurnBudgets(8).stricterWith(TurnBudgets(3)))
        assertEquals(TurnBudgets(3), TurnBudgets(3).stricterWith(TurnBudgets(8)))
    }

    @Test fun boundaryValuesRoundTrip() {
        for (value in listOf(0, Long.MAX_VALUE)) {
            val budget = TurnBudgets(value)
            assertEquals(budget, TurnBudgets.parse(budget.toStorageString()))
        }
    }

    @Test fun rejectsInvalidDomainValue() {
        assertThrows<IllegalArgumentException> { TurnBudgets(-1) }
    }

    @Test fun malformedAndObsoleteShapesAreRejected() {
        for (input in listOf(
            """""",
            """not json""",
            """{}""",
            """[]""",
            """{"maxOutputTokens":"2000"}""",
            """{"maxOutputTokens":2000.0}""",
            """{"maxOutputTokens":-1}""",
            """{"maxOutputTokens":2000,"extra":1}""",
            """{"maxOutputTokens":1,"maxOutputTokens":2}""",
            """{"maxOutputTokens":9223372036854775808}""",
            """{"maxSteps":8,"maxModelCalls":3,"maxInputTokens":12000,"maxOutputTokens":2000,"maxTotalTokens":14000}""",
        )) {
            assertThrows<IllegalArgumentException> { TurnBudgets.parse(input) }
        }
    }
}
