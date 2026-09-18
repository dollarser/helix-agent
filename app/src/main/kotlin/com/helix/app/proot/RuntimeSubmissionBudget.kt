package com.helix.app.proot

/** Samples a caller deadline once; preparation cannot gain time from a wall-clock adjustment. */
internal class RuntimeSubmissionBudget(
    deadlineEpochMs: Long,
    nowEpochMs: Long,
    private val startedElapsedMs: Long,
) {
    private val initialMillis: Long

    init {
        require(deadlineEpochMs >= 0 && nowEpochMs >= 0 && startedElapsedMs >= 0)
        initialMillis = (deadlineEpochMs - nowEpochMs).coerceIn(0, MAX_MILLIS)
    }

    fun remainingMillis(nowElapsedMs: Long): Long {
        if (nowElapsedMs < startedElapsedMs) return 0
        val remaining = initialMillis - (nowElapsedMs - startedElapsedMs)
        return if (remaining >= MIN_MILLIS) remaining else 0
    }

    companion object {
        private const val MIN_MILLIS = 1_000L
        private const val MAX_MILLIS = 3_600_000L
    }
}
