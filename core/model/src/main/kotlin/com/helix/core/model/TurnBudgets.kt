package com.helix.core.model

import com.helix.core.model.internal.Json
import com.helix.core.model.internal.parseJson
import com.helix.core.model.internal.requireInt
import com.helix.core.model.internal.requireLong
import com.helix.core.model.internal.requireObject

/**
 * Request output configuration and legacy snapshot fields. Only maxOutputTokens is an execution limit. The
 * other fields remain readable for old records; they never stop a Turn. Context capacity is owned by provider
 * settings.
 */
data class TurnBudgets(
    val maxSteps: Int,
    val maxModelCalls: Int,
    val maxInputTokens: Long,
    val maxOutputTokens: Long,
    val maxTotalTokens: Long,
) {
    init {
        require(maxSteps >= 1) { "maxSteps must be >= 1" }
        require(maxModelCalls >= 1) { "maxModelCalls must be >= 1" }
        require(maxInputTokens >= 0) { "maxInputTokens must be >= 0" }
        require(maxOutputTokens >= 0) { "maxOutputTokens must be >= 0" }
        require(maxTotalTokens >= 0) { "maxTotalTokens must be >= 0" }
    }

    /** Returns the element-wise stricter budget of `this` and `other`. */
    fun stricterWith(other: TurnBudgets): TurnBudgets =
        TurnBudgets(
            maxSteps = minOf(maxSteps, other.maxSteps),
            maxModelCalls = minOf(maxModelCalls, other.maxModelCalls),
            maxInputTokens = minOf(maxInputTokens, other.maxInputTokens),
            maxOutputTokens = minOf(maxOutputTokens, other.maxOutputTokens),
            maxTotalTokens = minOf(maxTotalTokens, other.maxTotalTokens),
        )

    fun toStorageString(): String {
        val pairs =
            listOf(
                "maxSteps" to Json.long(maxSteps.toLong()),
                "maxModelCalls" to Json.long(maxModelCalls.toLong()),
                "maxInputTokens" to Json.long(maxInputTokens),
                "maxOutputTokens" to Json.long(maxOutputTokens),
                "maxTotalTokens" to Json.long(maxTotalTokens),
            )
        return Json.objectBody(pairs)
    }

    companion object {
        private val FIELDS =
            listOf(
                "maxSteps",
                "maxModelCalls",
                "maxInputTokens",
                "maxOutputTokens",
                "maxTotalTokens",
            )

        fun parse(text: String): TurnBudgets {
            val fields = parseJson(text).requireObject("TurnBudgets", FIELDS)
            return TurnBudgets(
                maxSteps = fields.requireInt("maxSteps"),
                maxModelCalls = fields.requireInt("maxModelCalls"),
                maxInputTokens = fields.requireLong("maxInputTokens"),
                maxOutputTokens = fields.requireLong("maxOutputTokens"),
                maxTotalTokens = fields.requireLong("maxTotalTokens"),
            )
        }
    }
}
