package com.helix.core.agent

/** Summary prose target is not the billed output limit: reasoning also consumes output tokens. */
data class SummaryOutputBudget(
    val target: Long,
    val allowance: Long,
) {
    fun modelRequest(
        model: String,
        history: String,
        summaries: ContextSummaryFormat,
    ) = com.helix.core.model.ModelRequest(
        model = model,
        messages = summaries.summaryMessages(history, target),
        tools = emptyList(),
        maxOutputTokens = allowance,
        reasoning = com.helix.core.model.ReasoningEffort.OFF,
    )

    companion object {
        fun forRequest(
            input: Long,
            configuredOutput: Long,
            window: Long,
        ): SummaryOutputBudget {
            val allowance = minOf(16_384L, configuredOutput, window / 4)
            val target = minOf((input / 8).coerceIn(128L, 4096L), allowance)
            return SummaryOutputBudget(target, allowance)
        }
    }
}
