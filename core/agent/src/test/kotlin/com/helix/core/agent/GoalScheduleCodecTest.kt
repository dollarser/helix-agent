package com.helix.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * P1 (research doc section 14 "Scheduled Tasks"): [GoalScheduleCodec] — the canonical, round-trip
 * string form a [GoalSchedule] is stored in (one TEXT column on the Room goal row). Encoding is
 * lossless and locale-free; decoding is its exact inverse and fails closed on malformed input, so a
 * corrupt column can never become a wrong (or missing) schedule.
 */
class GoalScheduleCodecTest {
    private val ny = ZoneId.of("America/New_York")
    private val berlin = ZoneId.of("Europe/Berlin")

    @Test
    fun aOneTimeRoundTrips() {
        val s = GoalSchedule.OneTime(Instant.parse("2026-09-15T00:00:00Z"))
        assertEquals(s, GoalScheduleCodec.decode(GoalScheduleCodec.encode(s)))
    }

    @Test
    fun theOneTimeEncodingIsTheIsoInstant() {
        val encoded = GoalScheduleCodec.encode(GoalSchedule.OneTime(Instant.parse("2026-09-15T00:00:00Z")))
        assertEquals("once:2026-09-15T00:00:00Z", encoded)
    }

    @Test
    fun aDailyRoundTripsInItsOwnZone() {
        val s = GoalSchedule.Daily(LocalTime.of(9, 0), ny)
        assertEquals(s, GoalScheduleCodec.decode(GoalScheduleCodec.encode(s)))
    }

    @Test
    fun theDailyEncodingCarriesTimeAndZoneId() {
        val encoded = GoalScheduleCodec.encode(GoalSchedule.Daily(LocalTime.of(9, 0), berlin))
        assertEquals("daily:09:00[Europe/Berlin]", encoded)
    }

    @Test
    fun aDailyKeepsSubMinutePrecisionAcrossARoundTrip() {
        val s = GoalSchedule.Daily(LocalTime.of(9, 0, 45), ny)
        assertEquals(s, GoalScheduleCodec.decode(GoalScheduleCodec.encode(s)))
    }

    @Test
    fun aWeeklyRoundTrips() {
        val s = GoalSchedule.Weekly(DayOfWeek.MONDAY, LocalTime.of(9, 0), ny)
        assertEquals(s, GoalScheduleCodec.decode(GoalScheduleCodec.encode(s)))
    }

    @Test
    fun theWeeklyEncodingCarriesDayTimeAndZoneId() {
        val encoded = GoalScheduleCodec.encode(GoalSchedule.Weekly(DayOfWeek.MONDAY, LocalTime.of(9, 0), ny))
        assertEquals("weekly:MONDAY:09:00[America/New_York]", encoded)
    }

    @Test
    fun anExplicitUtcOffsetRoundTrips() {
        val s = GoalSchedule.Daily(LocalTime.of(6, 30), ZoneId.of("Z"))
        assertEquals(s, GoalScheduleCodec.decode(GoalScheduleCodec.encode(s)))
    }

    @Test
    fun malformedInputFailsClosed() {
        val malformed =
            listOf(
                "",
                "daily",
                "foo:bar",
                "daily:09:00",
                "daily:09:00UTC",
                "daily:[UTC]",
                "weekly:09:00[UTC]",
                "weekly:FOO:09:00[UTC]",
                "once:not-a-time",
                "daily:xx[UTC]",
                "daily:09:00[Not/AZone]",
            )
        malformed.forEach { text ->
            assertThrows(IllegalArgumentException::class.java) { GoalScheduleCodec.decode(text) }
        }
    }
}
