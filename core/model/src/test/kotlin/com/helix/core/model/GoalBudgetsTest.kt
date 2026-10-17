package com.helix.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class GoalBudgetsTest {
    @Test fun storageEncodingIsStableAndRoundTrips() {
        val value = GoalBudgets(7)
        assertEquals("""{"maxGoalRounds":7}""", value.toStorageString())
        assertEquals(value, GoalBudgets.parse(value.toStorageString()))
        assertEquals(value.toStorageString(), value.toStorageString())
    }

    @Test fun stricterAllowanceNeverRaisesEitherInput() {
        assertEquals(GoalBudgets(3), GoalBudgets(8).stricterWith(GoalBudgets(3)))
        assertEquals(GoalBudgets(3), GoalBudgets(3).stricterWith(GoalBudgets(8)))
    }

    @Test fun boundaryValuesRoundTrip() {
        for (value in listOf(1, Int.MAX_VALUE)) {
            val budget = GoalBudgets(value)
            assertEquals(budget, GoalBudgets.parse(budget.toStorageString()))
        }
    }

    @Test fun rejectsInvalidDomainValue() {
        assertThrows<IllegalArgumentException> { GoalBudgets(-1) }
        assertThrows<IllegalArgumentException> { GoalBudgets(0) }
    }

    @Test fun malformedAndObsoleteShapesAreRejected() {
        for (input in listOf(
            """""",
            """not json""",
            """{}""",
            """[]""",
            """{"maxGoalRounds":"7"}""",
            """{"maxGoalRounds":7.0}""",
            """{"maxGoalRounds":-1}""",
            """{"maxGoalRounds":7,"extra":1}""",
            """{"maxGoalRounds":1,"maxGoalRounds":2}""",
            """{"maxGoalRounds":9223372036854775808}""",
            """{"maxGoalRounds":0}""",
            """{"maxGoalRounds":2147483648}""",
            """{"maxModelCalls":10,"maxToolCalls":20,"maxTotalTokens":100000,""" +
                """"maxDurationMillis":3600000,"maxWakeDurationMillis":600000,"maxRetries":3,"maxGoalRounds":7}""",
        )) {
            assertThrows<IllegalArgumentException> { GoalBudgets.parse(input) }
        }
    }
}
