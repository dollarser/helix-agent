package com.helix.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoalBudgetFieldsTest {
    @Test
    fun malformedAndOverflowingBudgetsDoNotSave() {
        val valid = listOf("256")
        listOf("", "-1", "9223372036854775808").forEach { bad ->
            valid.indices.forEach { index ->
                assertNull(parseGoalBudgetFields(valid.toMutableList().also { it[index] = bad }))
            }
        }
        assertNull(parseGoalBudgetFields(valid.dropLast(1)))
        assertNull(parseGoalBudgetFields(valid.toMutableList().also { it[0] = Long.MAX_VALUE.toString() }))
        assertNull(parseGoalBudgetFields(valid.toMutableList().also { it[0] = "2147483648" }))
        assertEquals(256, parseGoalBudgetFields(valid)?.maxGoalRounds)
    }
}
