package com.helix.core.agent

/** Bounded evidence of repeated observations, never a tool-result cache or execution permission. */
object ToolLoopProgress {
    enum class Decision { CONTINUE, WARN, STOP }

    const val WINDOW = 12

    data class Fact(
        val fingerprint: String?,
        val awaitingExecution: Boolean = false,
    )

    fun evaluate(observations: List<String?>): Decision = evaluateFacts(observations.map { Fact(it) })

    /** Waiting is not progress. Repeated fresh observations warn; original leases and budgets still apply. */
    fun evaluateFacts(observations: List<Fact>): Decision {
        val tail = observations.takeLast(WINDOW)
        var decision = Decision.CONTINUE
        for (period in 1..2) {
            val pattern = tail.takeLast(period)
            if (pattern.size != period || pattern.any { it.fingerprint == null }) continue
            var repetitions = 0
            var end = tail.size
            while (end >= period && tail.subList(end - period, end) == pattern) {
                repetitions++
                end -= period
            }
            if (repetitions >= 6 && pattern.none { it.awaitingExecution }) return Decision.STOP
            if (repetitions >= 3) decision = Decision.WARN
        }
        return decision
    }
}
