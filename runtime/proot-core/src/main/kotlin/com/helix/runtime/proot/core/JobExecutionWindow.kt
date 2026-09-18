package com.helix.runtime.proot.core

/** One process-local budget, sampled at admission and shared by preparation and execution. */
class JobExecutionWindow(
    private val startedElapsedMs: Long,
    private val durationMs: Long,
) {
    init {
        require(startedElapsedMs >= 0 && durationMs > 0)
    }

    /** Never infer elapsed time across a clock reset or another process incarnation. */
    fun elapsedMs(nowElapsedMs: Long): Long? =
        if (nowElapsedMs < startedElapsedMs) null else nowElapsedMs - startedElapsedMs

    fun remainingMs(nowElapsedMs: Long): Long =
        if (nowElapsedMs < startedElapsedMs) {
            0
        } else {
            (durationMs - (nowElapsedMs - startedElapsedMs)).coerceAtLeast(0)
        }
}
