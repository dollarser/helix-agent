package com.helix.extensions.mobileuse.automation

import java.time.Duration

/** Zero explicitly means until stopped / no action quota. Parsing does not mutate grants. */
data class AutomationSessionOptions(
    val ttl: Duration,
    val maxActions: Int,
) {
    companion object {
        fun parse(
            minutes: String,
            actions: String,
        ): AutomationSessionOptions {
            val duration = requireNotNull(minutes.trim().toLongOrNull()) { "INVALID_DURATION" }
            val budget = requireNotNull(actions.trim().toIntOrNull()) { "INVALID_ACTION_BUDGET" }
            require(duration >= 0 && duration <= Long.MAX_VALUE / 60 && budget >= 0)
            return AutomationSessionOptions(Duration.ofMinutes(duration), budget)
        }
    }
}
