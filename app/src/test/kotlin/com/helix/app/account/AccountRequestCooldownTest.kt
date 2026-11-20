package com.helix.app.account

import com.helix.provider.api.wire.HttpFailureDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AccountRequestCooldownTest {
    @Test fun negativeMonotonicOriginPreservesWaitAndExpires() {
        var now = -100_000L
        val cooldown = AccountRequestCooldown { now }
        cooldown.record(1, "endpoint", HttpFailureDetails(429, retryAfterMillis = 30_000))
        now += 10_000
        val error = assertThrows(AccountRequestFailure::class.java) { cooldown.check(1, "endpoint") }
        assertEquals(20_000L, error.details.retryAfterMillis)
        cooldown.check(2, "endpoint")
        now += 20_000
        cooldown.check(1, "endpoint")
    }
}
