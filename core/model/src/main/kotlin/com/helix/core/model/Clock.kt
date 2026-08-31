package com.helix.core.model

import java.time.Instant

/**
 * Time source for the agent runtime, policy and audit layers. Tests must inject a deterministic
 * fake (security doc section 6.1: no real clock in unit tests).
 */
interface Clock {
    fun now(): Instant
}

/** Production [Clock] backed by the system UTC clock. */
class SystemClock : Clock {
    private val source: java.time.Clock = java.time.Clock.systemUTC()

    override fun now(): Instant = source.instant()
}
