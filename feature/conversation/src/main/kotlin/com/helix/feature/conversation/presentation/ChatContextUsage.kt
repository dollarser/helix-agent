package com.helix.feature.conversation.presentation

import com.helix.provider.api.ProviderContextSettings

/** A single request snapshot, never cumulative turn billing or an inferred model limit. */
data class ChatContextUsage(
    val inputTokens: Long? = null,
    val windowTokens: Long? = com.helix.provider.api.ProviderContextSettings.DEFAULT_WINDOW,
    val estimatedAfterCompaction: Boolean = false,
    val estimatedWindow: Boolean = false,
    val projectedTokens: Long? = null,
) {
    /** Last measured prompt plus retained surface growth; never cumulative billing. */
    val usedTokens: Long? get() = projectedTokens ?: inputTokens

    val fraction: Float?
        get() =
            usedTokens?.takeIf { it >= 0 }?.let { used ->
                windowTokens?.takeIf { it > 0 }?.let { (used.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }
            }

    val percentage: Long?
        get() =
            usedTokens?.takeIf { it >= 0 }?.let { used ->
                windowTokens?.takeIf { it > 0 }?.let { (used.toDouble() / it * PERCENT).toLong() }
            }

    /** Display whole percentages rounded down, including zero for usage below one percent. */
    val percentageLabel: String
        get() =
            when {
                percentage == null -> "?"
                else -> "$percentage%"
            }

    companion object {
        /** No conversation history has been submitted yet; missing telemetry remains unknown. */
        fun emptyConversation(): ChatContextUsage = ChatContextUsage(inputTokens = 0)

        private const val PERCENT = 100
    }
}
