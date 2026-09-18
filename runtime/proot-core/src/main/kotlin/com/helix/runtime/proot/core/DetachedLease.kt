package com.helix.runtime.proot.core

/** A non-renewable execution window. Wall time is for display, elapsed time enforces it. */
data class DetachedLease(
    val generation: String,
    val startedAtEpochMs: Long,
    val startedAtElapsedMs: Long,
    val durationMs: Long,
) {
    init {
        require(generation.matches(Regex("[a-zA-Z0-9-]{1,64}")))
        require(startedAtEpochMs >= 0 && startedAtElapsedMs >= 0)
        require(durationMs in MIN_MS..MAX_MS)
        require(startedAtEpochMs <= Long.MAX_VALUE - durationMs)
        require(startedAtElapsedMs <= Long.MAX_VALUE - durationMs)
    }

    val deadlineEpochMs: Long get() = startedAtEpochMs + durationMs

    fun remainingMs(
        currentGeneration: String,
        elapsedMs: Long,
    ): Long =
        if (currentGeneration != generation || elapsedMs < startedAtElapsedMs) {
            0L
        } else {
            (durationMs - (elapsedMs - startedAtElapsedMs)).coerceAtLeast(0L)
        }

    companion object {
        const val MIN_MS = 1_000L
        const val DEFAULT_MS = 300_000L
        const val MAX_MS = 1_800_000L

        /** Binding/handshake time spends the caller's window; never round an expired window up. */
        fun remainingSubmissionMillis(
            requestedMs: Long,
            budgetMs: Long,
            startedMs: Long,
            nowMs: Long,
        ): Long {
            require(requestedMs in MIN_MS..MAX_MS)
            require(startedMs >= 0)
            if (nowMs < startedMs || budgetMs < MIN_MS) return 0
            val remaining = minOf(requestedMs, budgetMs) - (nowMs - startedMs)
            return if (remaining >= MIN_MS) remaining else 0
        }

        fun create(
            generation: String,
            epochMs: Long,
            elapsedMs: Long,
            requestedMs: Long = DEFAULT_MS,
            remainingBudgetMs: Long,
        ): DetachedLease {
            require(requestedMs in MIN_MS..MAX_MS)
            require(remainingBudgetMs >= MIN_MS) { "Execution budget exhausted" }
            return DetachedLease(generation, epochMs, elapsedMs, minOf(requestedMs, remainingBudgetMs))
        }
    }
}
