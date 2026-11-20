package com.helix.app.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AccountNoticeScheduleTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun failedAttemptSurvivesRestartAndAccountSwitch() {
        val file = temporary.newFile()
        val schedule = AccountNoticeSchedule(file)
        assertTrue(schedule.claim("one", 1000))
        assertFalse(AccountNoticeSchedule(file).claim("one", 1001))
        assertTrue(schedule.claim("two", 1001))
        assertTrue(AccountNoticeSchedule(file).claim("one", 1000 + AccountNoticeSchedule.INTERVAL_MILLIS))
    }

    @Test fun successfulManualListResetsIntervalAndReadOnlyUpdatesBadge() {
        val schedule = AccountNoticeSchedule()
        schedule.record("one", 4, 1000)
        schedule.record("one", 3)
        assertEquals(3, schedule.unread("one"))
        assertEquals(null, schedule.unread("two"))
        assertFalse(schedule.claim("one", 1001))
        assertTrue(schedule.claim("one", 1000 + AccountNoticeSchedule.INTERVAL_MILLIS))
    }

    @Test fun unavailablePersistenceDisablesAutomaticRequestsButAllowsManualState() {
        val blocker = temporary.newFile()
        val file = File(blocker, "notice-checks.properties")
        val schedule = AccountNoticeSchedule(file)
        assertFalse(schedule.claim("one", 1000))
        assertFalse(schedule.claim("one", 1001))
        assertFalse(AccountNoticeSchedule(file).claim("one", 1001))
        schedule.record("one", 7, 1002)
        assertEquals(7, schedule.unread("one"))
    }

    @Test fun extremeTimestampDoesNotOverflowAndBypassInterval() {
        val schedule = AccountNoticeSchedule()
        assertTrue(schedule.claim("one", Long.MAX_VALUE - 1))
        assertFalse(schedule.claim("one", Long.MAX_VALUE))
        assertFalse(schedule.claim("one", 1000))
    }

    @Test fun clockRollbackDoesNotBypassInterval() {
        val schedule = AccountNoticeSchedule()
        assertTrue(schedule.claim("one", 2000))
        assertFalse(schedule.claim("one", 1000))
    }
}
