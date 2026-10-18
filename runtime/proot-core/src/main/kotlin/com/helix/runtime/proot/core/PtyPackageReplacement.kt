package com.helix.runtime.proot.core

import kotlin.math.abs

/** Platform facts only. A version number, elapsed lease or absent PID cannot establish this proof. */
data class PtyPackageReplacement(
    val bootCount: Int?,
    val updatedAtEpochMs: Long,
    val runtimeKilledAtEpochMs: Long,
    val observedAtEpochMs: Long,
    val observedAtElapsedMs: Long,
) {
    fun provesExit(origin: PtySessionOrigin): Boolean {
        val sameBoot = bootCount != null && bootCount == origin.bootCount
        val clockConsistent =
            abs(
                (observedAtEpochMs - observedAtElapsedMs) - (origin.createdAtEpochMs - origin.startedAtElapsedMs),
            ) <= CLOCK_TOLERANCE_MS
        return sameBoot && clockConsistent &&
            updatedAtEpochMs > origin.createdAtEpochMs && updatedAtEpochMs <= observedAtEpochMs &&
            runtimeKilledAtEpochMs > origin.createdAtEpochMs && runtimeKilledAtEpochMs <= observedAtEpochMs
    }

    companion object {
        private const val CLOCK_TOLERANCE_MS = 2000L
    }
}
