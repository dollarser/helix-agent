package com.helix.core.model

import com.helix.core.model.internal.Json
import com.helix.core.model.internal.parseJson
import com.helix.core.model.internal.requireInt
import com.helix.core.model.internal.requireObject

/** Automatic continuation allowance. Usage is persisted independently and does not stop a Goal. */
data class GoalBudgets(
    val maxGoalRounds: Int = 256,
) {
    init {
        require(maxGoalRounds >= 1) { "maxGoalRounds must be positive" }
    }

    fun stricterWith(other: GoalBudgets): GoalBudgets = GoalBudgets(minOf(maxGoalRounds, other.maxGoalRounds))

    fun toStorageString(): String = Json.objectBody(listOf("maxGoalRounds" to Json.long(maxGoalRounds.toLong())))

    companion object {
        fun parse(text: String): GoalBudgets {
            val fields = parseJson(text).requireObject("GoalBudgets", listOf("maxGoalRounds"))
            return GoalBudgets(fields.requireInt("maxGoalRounds"))
        }
    }
}
