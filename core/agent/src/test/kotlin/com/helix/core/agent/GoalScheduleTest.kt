package com.helix.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * P1 (research doc section 14 "Scheduled Tasks"): [GoalSchedule] — the first version supports
 * One-time, Daily and Weekly only. [GoalSchedule.nextFireTime] returns the next fire instant
 * strictly after an injected [now] (null once a one-time is exhausted); the recurring kinds roll
 * forward within their own [ZoneId]. The schedule decides *when* to fire, never *whether* —
 * high-risk actions still pass through runtime approval.
 */
class GoalScheduleTest {
    private val utc = ZoneId.of("UTC")

    private fun instant(value: String): Instant = Instant.parse(value)

    @Test
    fun aOneTimeInFutureIsItsNextFire() {
        val s = GoalSchedule.OneTime(instant("2026-09-15T00:00:00Z"))
        assertEquals(instant("2026-09-15T00:00:00Z"), s.nextFireTime(instant("2026-09-12T08:00:00Z")))
    }

    @Test
    fun aOneTimeReachedIsExhausted() {
        val s = GoalSchedule.OneTime(instant("2026-09-15T00:00:00Z"))
        assertNull(s.nextFireTime(instant("2026-09-15T00:00:00Z")))
    }

    @Test
    fun aOneTimeInPastIsExhausted() {
        val s = GoalSchedule.OneTime(instant("2026-09-15T00:00:00Z"))
        assertNull(s.nextFireTime(instant("2026-09-20T00:00:00Z")))
    }

    @Test
    fun aOneTimeIsNotRecurring() {
        assertFalse(GoalSchedule.OneTime(instant("2026-09-15T00:00:00Z")).isRecurring)
    }

    @Test
    fun aDailyLaterTodayFiresToday() {
        val s = GoalSchedule.Daily(LocalTime.of(9, 0), utc)
        assertEquals(instant("2026-09-12T09:00:00Z"), s.nextFireTime(instant("2026-09-12T08:00:00Z")))
    }

    @Test
    fun aDailyWhoseTimePassedFiresTomorrow() {
        val s = GoalSchedule.Daily(LocalTime.of(9, 0), utc)
        assertEquals(instant("2026-09-13T09:00:00Z"), s.nextFireTime(instant("2026-09-12T09:30:00Z")))
    }

    @Test
    fun aDailyAtExactlyItsTimeFiresTomorrow() {
        val s = GoalSchedule.Daily(LocalTime.of(9, 0), utc)
        assertEquals(instant("2026-09-13T09:00:00Z"), s.nextFireTime(instant("2026-09-12T09:00:00Z")))
    }

    @Test
    fun aDailyRollsForwardInItsOwnZoneNotUtc() {
        val ny = ZoneId.of("America/New_York") // EDT (UTC-4) in September
        val s = GoalSchedule.Daily(LocalTime.of(22, 0), ny)
        // 14:00Z == 10:00 EDT the same day; the 22:00 EDT fire is 02:00Z the next day.
        assertEquals(instant("2026-09-13T02:00:00Z"), s.nextFireTime(instant("2026-09-12T14:00:00Z")))
    }

    @Test
    fun aDailyIsRecurring() {
        assertTrue(GoalSchedule.Daily(LocalTime.of(9, 0), utc).isRecurring)
    }

    @Test
    fun aWeeklyOnItsTargetDayAheadFiresThatDay() {
        val s = GoalSchedule.Weekly(DayOfWeek.MONDAY, LocalTime.of(9, 0), utc)
        // now is Sunday 2026-09-13; the next Monday 09:00Z is 2026-09-14.
        assertEquals(instant("2026-09-14T09:00:00Z"), s.nextFireTime(instant("2026-09-13T00:00:00Z")))
    }

    @Test
    fun aWeeklyTodayInFutureFiresToday() {
        val s = GoalSchedule.Weekly(DayOfWeek.SATURDAY, LocalTime.of(9, 0), utc)
        // now is Saturday 2026-09-12 before 09:00Z -> fires today.
        assertEquals(instant("2026-09-12T09:00:00Z"), s.nextFireTime(instant("2026-09-12T08:00:00Z")))
    }

    @Test
    fun aWeeklyTodayAfterItsTimeFiresNextWeek() {
        val s = GoalSchedule.Weekly(DayOfWeek.SATURDAY, LocalTime.of(9, 0), utc)
        // now is Saturday 2026-09-12 after 09:00Z -> next Saturday is 2026-09-19.
        assertEquals(instant("2026-09-19T09:00:00Z"), s.nextFireTime(instant("2026-09-12T10:00:00Z")))
    }

    @Test
    fun aWeeklyOnItsDayAtExactlyItsTimeFiresNextWeek() {
        val s = GoalSchedule.Weekly(DayOfWeek.SATURDAY, LocalTime.of(9, 0), utc)
        assertEquals(instant("2026-09-19T09:00:00Z"), s.nextFireTime(instant("2026-09-12T09:00:00Z")))
    }

    @Test
    fun aWeeklyWrapsAroundTheEndOfWeek() {
        val s = GoalSchedule.Weekly(DayOfWeek.SUNDAY, LocalTime.of(9, 0), utc)
        // now is Monday 2026-09-14; the next Sunday is 2026-09-20 (six days ahead).
        assertEquals(instant("2026-09-20T09:00:00Z"), s.nextFireTime(instant("2026-09-14T00:00:00Z")))
    }

    @Test
    fun aWeeklyIsRecurring() {
        assertTrue(GoalSchedule.Weekly(DayOfWeek.MONDAY, LocalTime.of(9, 0), utc).isRecurring)
    }

    @Test
    fun theDescribeLabelsAreTheScheduleRule() {
        assertEquals("Once", GoalSchedule.OneTime(instant("2026-09-15T00:00:00Z")).describe())
        assertEquals("Daily at 09:00", GoalSchedule.Daily(LocalTime.of(9, 0), utc).describe())
        assertEquals(
            "Weekly on Monday at 09:00",
            GoalSchedule.Weekly(DayOfWeek.MONDAY, LocalTime.of(9, 0), utc).describe(),
        )
    }

    @Test
    fun theClockLabelZeroPadsHourAndMinute() {
        assertEquals("Daily at 05:04", GoalSchedule.Daily(LocalTime.of(5, 4), utc).describe())
    }
}
