package com.helix.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TechnicalRecoveryTest {
    @Test fun exponentialDelayHasBoundedJitterAndHardAttemptCap() {
        assertEquals(800L, TechnicalRecovery.delayMillis(0, 0, jitter = 0.0))
        assertEquals(1200L, TechnicalRecovery.delayMillis(0, 0, jitter = 1.0))
        assertEquals(2000L, TechnicalRecovery.delayMillis(1, 0, jitter = 0.5))
        assertNull(TechnicalRecovery.delayMillis(2, 0))
    }

    @Test fun originalDeadlineAndRecoveryWindowBothLimitAdmission() {
        assertNull(TechnicalRecovery.delayMillis(0, 0, remainingMillis = 500))
        assertNull(TechnicalRecovery.delayMillis(0, 10_000))
        assertNull(TechnicalRecovery.delayMillis(1, 9_000, jitter = 0.5))
    }
}
