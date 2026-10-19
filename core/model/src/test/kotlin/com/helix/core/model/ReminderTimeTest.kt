package com.helix.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

class ReminderTimeTest {
    private fun time(
        local: String,
        zone: String = "UTC",
        repeat: ReminderRepeat = ReminderRepeat.MONTHLY,
    ) = ReminderTime(LocalDateTime.parse(local), zone, repeat)

    private fun epoch(text: String) = Instant.parse(text).toEpochMilli()

    @Test fun monthlyReturnsToAnchorAfterShortMonth() {
        val value = time("2028-01-31T09:00")
        assertEquals(epoch("2028-02-29T09:00:00Z"), value.next(value.first()))
        assertEquals(epoch("2028-03-31T09:00:00Z"), value.next(epoch("2028-02-29T09:00:00Z")))
    }

    @Test fun skipsYearsWithoutReplaying() {
        val value = time("2020-01-31T09:00")
        assertEquals(epoch("2030-02-28T09:00:00Z"), value.next(epoch("2030-02-01T00:00:00Z")))
    }

    @Test fun dailyPreservesWallTimeAcrossDst() {
        val value = time("2026-03-07T09:00", "America/New_York", ReminderRepeat.DAILY)
        assertEquals(23 * 3600_000L, requireNotNull(value.next(value.first())) - value.first())
    }

    @Test fun gapShiftsAndOverlapUsesEarlierOffset() {
        assertEquals(epoch("2026-03-08T07:30:00Z"), time("2026-03-08T02:30", "America/New_York").first())
        assertEquals(epoch("2026-11-01T05:30:00Z"), time("2026-11-01T01:30", "America/New_York").first())
    }

    @Test fun weeklyStaysOnWeekdayAndOnceDoesNotRepeat() {
        val value = time("2026-10-19T09:00", repeat = ReminderRepeat.WEEKLY)
        assertEquals(epoch("2026-11-09T09:00:00Z"), value.next(epoch("2026-11-03T10:00:00Z")))
        val once = time("2026-10-19T09:00", repeat = ReminderRepeat.ONCE)
        assertNull(once.next(once.first()))
        assertEquals(once.first(), once.next(once.first() - 1))
    }
}
