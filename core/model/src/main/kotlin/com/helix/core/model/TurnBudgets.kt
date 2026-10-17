package com.helix.core.model

import com.helix.core.model.internal.Json
import com.helix.core.model.internal.parseJson
import com.helix.core.model.internal.requireLong
import com.helix.core.model.internal.requireObject

/** Per-model-call output allowance. Context capacity and cumulative usage are separate facts. */
data class TurnBudgets(
    val maxOutputTokens: Long,
) {
    init {
        require(maxOutputTokens >= 0) { "maxOutputTokens must not be negative" }
    }

    fun stricterWith(other: TurnBudgets): TurnBudgets = TurnBudgets(minOf(maxOutputTokens, other.maxOutputTokens))

    fun toStorageString(): String = Json.objectBody(listOf("maxOutputTokens" to Json.long(maxOutputTokens)))

    companion object {
        fun parse(text: String): TurnBudgets {
            val fields = parseJson(text).requireObject("TurnBudgets", listOf("maxOutputTokens"))
            return TurnBudgets(fields.requireLong("maxOutputTokens"))
        }
    }
}
