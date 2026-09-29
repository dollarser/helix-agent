package com.helix.app.agent

/** Bounded evidence of repeated observations, never a tool-result cache or execution permission. */
internal object ToolLoopProgress {
    enum class Decision { CONTINUE, WARN, STOP }

    const val WINDOW = 12
    val EXHAUSTED =
        com.helix.app.chat.packagedPromptTemplates
            .text("loop-exhausted")
            .trim()

    val WARNING =
        com.helix.app.chat.packagedPromptTemplates
            .text("loop-warning")
            .trim()

/** Null marks progress, a mutation, uncertain effects, or an explicitly live observation. */
    fun evaluate(observations: List<String?>): Decision {
        val tail = observations.takeLast(WINDOW)
        var decision = Decision.CONTINUE
        for (period in 1..2) {
            val pattern = tail.takeLast(period)
            if (pattern.size != period || pattern.any { it == null }) continue
            var repetitions = 0
            var end = tail.size
            while (end >= period && tail.subList(end - period, end) == pattern) {
                repetitions++
                end -= period
            }
            if (repetitions >= 6) return Decision.STOP
            if (repetitions >= 3) decision = Decision.WARN
        }
        return decision
    }
}
