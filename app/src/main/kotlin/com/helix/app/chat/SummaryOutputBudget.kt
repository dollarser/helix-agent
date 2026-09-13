package com.helix.app.chat

/** Summary prose target is not the billed output limit: reasoning also consumes output tokens. */
internal data class SummaryOutputBudget(
    val target: Long,
    val allowance: Long,
) {
    companion object {
        fun forRequest(
            input: Long,
            configuredOutput: Long,
            window: Long,
        ): SummaryOutputBudget {
            val allowance = minOf(4096L, configuredOutput, window / 4)
            val target = minOf((input / 8).coerceIn(2048L, 4096L), allowance)
            return SummaryOutputBudget(target, allowance)
        }
    }
}
