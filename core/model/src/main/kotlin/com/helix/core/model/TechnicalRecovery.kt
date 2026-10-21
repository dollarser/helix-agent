package com.helix.core.model

import kotlin.random.Random

/** Timing only; the adapter must first prove that repeating its operation is permitted. */
object TechnicalRecovery {
    const val WINDOW_MILLIS = 10_000L
    const val MAX_RETRIES = 2

    fun delayMillis(
        retries: Int,
        elapsedMillis: Long,
        remainingMillis: Long = Long.MAX_VALUE,
        jitter: Double = Random.nextDouble(),
    ): Long? {
        require(jitter in 0.0..1.0 && elapsedMillis >= 0)
        if (retries !in 0 until MAX_RETRIES) return null
        val remaining = minOf(WINDOW_MILLIS - elapsedMillis, remainingMillis)
        val delay = ((1_000L shl retries) * (0.8 + jitter * 0.4)).toLong()
        return delay.takeIf { it < remaining }
    }
}
