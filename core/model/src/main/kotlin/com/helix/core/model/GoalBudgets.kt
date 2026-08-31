package com.helix.core.model

import com.helix.core.model.internal.Json
import com.helix.core.model.internal.parseJson
import com.helix.core.model.internal.requireInt
import com.helix.core.model.internal.requireLong
import com.helix.core.model.internal.requireObject

/**
 * Hard limits for a persistent Goal across all of its runs (modes doc section 6.1:
 * "GoalBudgets at least include max model calls, tool calls, cumulative tokens, run duration,
 * single-wake duration and failure retry count").
 *
 * Unlike [TurnBudgets] these are goal-lifetime limits: consumed usage is accumulated by the
 * Goal reducer across wakes. Exhaustion parks the goal in PAUSED (or FAILED when retryable
 * failures exhaust [maxRetries]) - never COMPLETED. A zero duration/token budget means the goal
 * may not spend any; a zero [maxRetries] means a failed wake fails the goal.
 *
 * Storage encoding: fixed-order canonical JSON (see ADR-0001).
 */
data class GoalBudgets(
    val maxModelCalls: Int,
    val maxToolCalls: Int,
    val maxTotalTokens: Long,
    val maxDurationMillis: Long,
    val maxWakeDurationMillis: Long,
    val maxRetries: Int,
) {
    init {
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
            val fields = parseJson(text).requireObject("GoalBudgets", FIELDS)
            return GoalBudgets(
                maxModelCalls = fields.requireInt("maxModelCalls"),
                maxToolCalls = fields.requireInt("maxToolCalls"),
                maxTotalTokens = fields.requireLong("maxTotalTokens"),
                maxDurationMillis = fields.requireLong("maxDurationMillis"),
                maxWakeDurationMillis = fields.requireLong("maxWakeDurationMillis"),
                maxRetries = fields.requireInt("maxRetries"),
            )
        }
    }
}
