package com.helix.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ClockTest {
    @Test
    fun systemClockIsMonotonicAndSane() {
        val clock = SystemClock()
        val first = clock.now()
        val second = clock.now()
        assertTrue("second reading must not precede first", !second.isBefore(first))
        assertTrue("reading must be after 2020", first.isAfter(Instant.parse("2020-01-01T00:00:00Z")))
        assertTrue("reading must be before 2100", first.isBefore(Instant.parse("2100-01-01T00:00:00Z")))
    }

    @Test
    fun fakeClockDrivesDeterministicTime() {
        var fixed = Instant.parse("2026-08-31T00:00:00Z")
        val fake =
            object : Clock {
                override fun now(): Instant = fixed
            }
        assertEquals(fixed, fake.now())
        fixed = fixed.plusSeconds(5)
        assertEquals(fixed, fake.now())
    }
}
