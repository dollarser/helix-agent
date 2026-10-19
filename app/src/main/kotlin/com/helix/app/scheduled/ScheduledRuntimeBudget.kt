package com.helix.app.scheduled

import com.helix.core.storage.scheduled.ScheduledAgentRule
import java.math.BigDecimal

/** A disclosed initial value, never a product-wide execution ceiling. */
object ScheduledRuntimeBudget {
    const val DEFAULT_MILLIS = 120_000L

    fun parseSeconds(text: String): Long =
        BigDecimal(text.trim()).multiply(BigDecimal(1000)).longValueExact().also { require(it > 0) }

    fun seconds(millis: Long): String {
        require(millis > 0)
        return BigDecimal.valueOf(millis, 3).stripTrailingZeros().toPlainString()
    }
}

internal val ScheduledAgentRule.runtimeBudgetMillis: Long
    get() =
        if (scopeJson.isBlank()) {
            ScheduledRuntimeBudget.DEFAULT_MILLIS
        } else {
            ScheduledAgentScope
                .decode(
                    scopeJson,
                ).maxRuntimeMillis
        }
