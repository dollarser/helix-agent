package com.helix.core.model

import com.helix.core.model.internal.Json
import com.helix.core.model.internal.parseJson
import com.helix.core.model.internal.requireInt
import com.helix.core.model.internal.requireLong
import com.helix.core.model.internal.requireObject

/**
 * Automatic continuation allowance. maxGoalRounds is the only Goal execution allowance. Legacy resource and
 * retry fields remain serialized for old records and are not enforced. Usage remains independently persisted.
 */
data class GoalBudgets(
    val maxModelCalls: Int,
    val maxToolCalls: Int,
    val maxTotalTokens: Long,
    val maxDurationMillis: Long,
    val maxWakeDurationMillis: Long,
    val maxRetries: Int,
    val maxGoalRounds: Int = 256,
) {
    init {
        require(maxGoalRounds >= 1) { "maxGoalRounds must be positive" }
        require(maxModelCalls >= 1) { "maxModelCalls must be >= 1" }
        require(maxToolCalls >= 1) { "maxToolCalls must be >= 1" }
        require(maxTotalTokens >= 0) { "maxTotalTokens must be >= 0" }
        require(maxDurationMillis >= 0) { "maxDurationMillis must be >= 0" }
        require(maxWakeDurationMillis >= 0) { "maxWakeDurationMillis must be >= 0" }
        require(maxRetries >= 0) { "maxRetries must be >= 0" }
    }

    /** Returns the element-wise stricter budget of `this` and `other`. */
    fun stricterWith(other: GoalBudgets): GoalBudgets =
        GoalBudgets(
            maxModelCalls = minOf(maxModelCalls, other.maxModelCalls),
            maxToolCalls = minOf(maxToolCalls, other.maxToolCalls),
            maxTotalTokens = minOf(maxTotalTokens, other.maxTotalTokens),
            maxDurationMillis = minOf(maxDurationMillis, other.maxDurationMillis),
            maxWakeDurationMillis = minOf(maxWakeDurationMillis, other.maxWakeDurationMillis),
            maxRetries = minOf(maxRetries, other.maxRetries),
            maxGoalRounds = minOf(maxGoalRounds, other.maxGoalRounds),
        )

    fun toStorageString(): String {
        val pairs =
            listOf(
                "maxModelCalls" to Json.long(maxModelCalls.toLong()),
                "maxToolCalls" to Json.long(maxToolCalls.toLong()),
                "maxTotalTokens" to Json.long(maxTotalTokens),
                "maxDurationMillis" to Json.long(maxDurationMillis),
                "maxWakeDurationMillis" to Json.long(maxWakeDurationMillis),
                "maxRetries" to Json.long(maxRetries.toLong()),
                "maxGoalRounds" to Json.long(maxGoalRounds.toLong()),
            )
        return Json.objectBody(pairs)
    }

    companion object {
        private val FIELDS =
            listOf(
                "maxModelCalls",
                "maxToolCalls",
                "maxTotalTokens",
                "maxDurationMillis",
                "maxWakeDurationMillis",
                "maxRetries",
            )

        fun parse(text: String): GoalBudgets {
            val node = parseJson(text)
            val keys = (node as? com.helix.core.model.internal.JsonNode.Obj)?.entries?.map { it.first }.orEmpty()
            val fields =
                node.requireObject(
                    "GoalBudgets",
                    if ("maxGoalRounds" in
                        keys
                    ) {
                        FIELDS + "maxGoalRounds"
                    } else {
                        FIELDS
                    },
                )
            return GoalBudgets(
                maxModelCalls = fields.requireInt("maxModelCalls"),
                maxToolCalls = fields.requireInt("maxToolCalls"),
                maxTotalTokens = fields.requireLong("maxTotalTokens"),
                maxDurationMillis = fields.requireLong("maxDurationMillis"),
                maxWakeDurationMillis = fields.requireLong("maxWakeDurationMillis"),
                maxRetries = fields.requireInt("maxRetries"),
                maxGoalRounds = if ("maxGoalRounds" in fields) fields.requireInt("maxGoalRounds") else 256,
            )
        }
    }
}
