package com.helix.tools.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class AutomationExpiryTest {
    @Test fun subMillisecondRemaindersNeverExpireEarly() {
        val now = Instant.parse("2026-10-03T00:00:00.123456789Z")
        assertEquals(1L, automationExpiryDelayMillis(now, now.plusNanos(1)))
        assertEquals(1_001L, automationExpiryDelayMillis(now, now.plusSeconds(1).plusNanos(1)))
        assertEquals(1_000L, automationExpiryDelayMillis(now, now.plusSeconds(1)))
    }

    @Test fun longAndUnlimitedGrantsDoNotOverflowTheScheduler() {
        assertNull(automationExpiryDelayMillis(Instant.EPOCH, Instant.MAX))
        assertEquals(86_400_000L, automationExpiryDelayMillis(Instant.EPOCH, Instant.MAX.minusSeconds(1)))
        assertEquals(0L, automationExpiryDelayMillis(Instant.EPOCH, Instant.EPOCH))
    }
}
