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
        focus: String? = null,
    ) = com.helix.core.model.ModelRequest(
        model = model,
        messages = summaries.summaryMessages(history, target, focus),
        tools = emptyList(),
        maxOutputTokens = allowance,
        reasoning = com.helix.core.model.ReasoningEffort.OFF,
    )

    companion object {
        /** Window-scaled uncertainty margin; still an estimate, never an exact tokenizer guarantee. */
        fun reserve(window: Long): Long = minOf(window / 4, maxOf(2048L, (window + 9) / 10))

        /** Bind the fully assembled request, including instructions and optional manual focus. */
        fun bound(
            request: com.helix.core.model.ModelRequest,
            window: Long,
            inputScale: Double = 1.0,
            serverOutputLimit: Long? = null,
        ): com.helix.core.model.ModelRequest {
            require(inputScale.isFinite() && inputScale >= 1.0)
            val input = kotlin.math.ceil(ModelInputEstimate.of(request).total * inputScale).toLong()
            val remaining = window - input - reserve(window)
            if (remaining <= 0) throw ContextCapacityException("CONTEXT_WINDOW_LIMIT")
            return request.copy(maxOutputTokens = minOf(remaining, serverOutputLimit ?: remaining))
        }
    }
}
