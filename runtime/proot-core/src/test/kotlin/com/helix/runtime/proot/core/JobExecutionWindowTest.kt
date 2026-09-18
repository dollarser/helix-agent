package com.helix.runtime.proot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class JobExecutionWindowTest {
    @Test fun queueAndPreparationSpendTheSameExecutionWindow() {
        val window = JobExecutionWindow(100, 3_000)
        assertEquals(3_000L, window.remainingMs(100))
        assertEquals(1_000L, window.remainingMs(2_100))
        assertEquals(0L, window.remainingMs(3_100))
        assertEquals(0L, window.remainingMs(9_100))
    }

    @Test fun clockRollbackExpiresAndLargeElapsedValuesDoNotOverflow() {
        assertEquals(0L, JobExecutionWindow(100, 3_000).remainingMs(99))
        assertEquals(0L, JobExecutionWindow(0, 3_000).remainingMs(Long.MAX_VALUE))
        assertEquals(2_999L, JobExecutionWindow(Long.MAX_VALUE - 1, 3_000).remainingMs(Long.MAX_VALUE))
    }

    @Test fun invalidWindowsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { JobExecutionWindow(-1, 1) }
        assertThrows(IllegalArgumentException::class.java) { JobExecutionWindow(0, 0) }
    }
}
