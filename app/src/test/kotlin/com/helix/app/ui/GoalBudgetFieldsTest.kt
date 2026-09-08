package com.helix.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoalBudgetFieldsTest {
    @Test
    fun malformedAndOverflowingBudgetsDoNotSave() {
        val valid = listOf("2", "4", "1000", "60", "10", "0")
        listOf("", "-1", "9223372036854775808").forEach { bad ->
            valid.indices.forEach { index ->
                assertNull(parseGoalBudgetFields(valid.toMutableList().also { it[index] = bad }))
            }
        }
        assertNull(parseGoalBudgetFields(valid.dropLast(1)))
        assertNull(parseGoalBudgetFields(valid.toMutableList().also { it[3] = Long.MAX_VALUE.toString() }))
        assertNull(parseGoalBudgetFields(valid.toMutableList().also { it[0] = "2147483648" }))
        assertEquals(60_000L, parseGoalBudgetFields(valid)?.maxDurationMillis)
    }
}
