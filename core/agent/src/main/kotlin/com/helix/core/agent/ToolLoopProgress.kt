package com.helix.core.agent

/** Bounded evidence of repeated observations, never a tool-result cache or execution permission. */
object ToolLoopProgress {
    enum class Decision { CONTINUE, WARN, REASSESS, STOP }

    const val WINDOW = 24

    data class WorkflowStep(
        val key: String,
        val observation: Boolean,
    )

    /** Cadence is not evidence of no progress. Successful changing observations can only prompt review. */
    fun repeatingObservationWorkflow(steps: List<WorkflowStep?>): Boolean {
        val tail = steps.takeLast(WINDOW)
        for (period in 1..6) {
            val pattern = tail.takeLast(period)
            if (pattern.size != period || pattern.any { it == null } ||
                pattern.none { it?.observation == true }
            ) {
                continue
            }
            if (tail.size >= period * 3 &&
                tail.takeLast(period * 3).chunked(period).all { it == pattern }
            ) {
                return true
            }
        }
        return false
    }

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
