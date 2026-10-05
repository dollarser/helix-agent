package com.helix.extensions.mobileuse.automation

import org.junit.Assert.assertEquals
import org.junit.Test

class AutomationPlatformActionTest {
    @Test fun completedActionRunsExactlyOnce() {
        var calls = 0
        val result =
            performPlatformAutomationAction {
                calls++
                true
            }
        assertEquals(1, calls)
        assertEquals(AutomationActionStatus.SUCCEEDED, result.status)
    }

    @Test fun falseResultRemainsAFailure() {
        var calls = 0
        val result =
            performPlatformAutomationAction {
                calls++
                false
            }
        assertEquals(1, calls)
        assertEquals(AutomationActionStatus.ACTION_FAILED, result.status)
    }

    @Test fun thrownAcknowledgementIsUnknownAndIsNeverRetried() {
        var calls = 0
        val result =
            performPlatformAutomationAction {
                calls++
                error("acknowledgement lost")
            }
        assertEquals(1, calls)
        assertEquals(AutomationActionStatus.ACTION_OUTCOME_UNKNOWN, result.status)
    }
}
