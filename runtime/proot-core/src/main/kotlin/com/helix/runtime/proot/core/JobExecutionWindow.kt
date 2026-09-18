package com.helix.runtime.proot.core

/** One process-local budget, sampled at admission and shared by preparation and execution. */
class JobExecutionWindow(
    private val startedElapsedMs: Long,
    private val durationMs: Long,
) {
    init {
        require(startedElapsedMs >= 0 && durationMs > 0)
    }

    fun remainingMs(nowElapsedMs: Long): Long =
        if (nowElapsedMs < startedElapsedMs) {
            0
        } else {
            (durationMs - (nowElapsedMs - startedElapsedMs)).coerceAtLeast(0)
        }
}
