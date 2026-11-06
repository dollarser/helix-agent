package com.helix.app.automation.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AdbLaunchTicketsTest {
    private var time = 0L
    private var generation = 0
    private val tickets = AdbLaunchTickets<String>({ time }) { (++generation).toString(16).padStart(64, '0') }
    private val identity = AdbLaunchTickets.Identity("a".repeat(32), 123, 10123, "/data/app/current/base.apk")

    @Test fun matchingLaunchIsIdempotentAndConsumptionCannotReplay() {
        val nonce = tickets.begin(identity, "lifetime")
        assertEquals(nonce, tickets.begin(identity, "lifetime"))
        tickets.verify(identity, nonce)
        tickets.consume(identity, nonce)
        assertThrows(IllegalStateException::class.java) { tickets.consume(identity, nonce) }
        val next = tickets.begin(identity, "lifetime")
        assertNotEquals(nonce, next)
        assertThrows(IllegalStateException::class.java) { tickets.verify(identity, nonce) }
    }

    @Test fun changedPidApkUserLaunchNonceOrLifetimeCannotRetargetATicket() {
        val nonce = tickets.begin(identity, "lifetime")
        for (other in listOf(
            identity.copy(pid = 124),
            identity.copy(apk = "/data/app/updated/base.apk"),
            identity.copy(ownerUid = 1010123),
            identity.copy(launch = "b".repeat(32)),
        )) {
            assertThrows(IllegalStateException::class.java) { tickets.verify(other, nonce) }
            assertThrows(IllegalStateException::class.java) { tickets.begin(other, "lifetime") }
        }
        assertThrows(IllegalStateException::class.java) { tickets.begin(identity, "other-lifetime") }
        assertThrows(IllegalStateException::class.java) { tickets.verify(identity, "0".repeat(64)) }
        tickets.consume(identity, nonce)
    }

    @Test fun expiredChallengeDoesNotRefreshOnAttachAndNewLaunchGetsNewNonce() {
        val nonce = tickets.begin(identity, "lifetime")
        time = 30_000
        assertThrows(IllegalStateException::class.java) { tickets.verify(identity, nonce) }
        assertThrows(IllegalStateException::class.java) { tickets.consume(identity, nonce) }
        val next = tickets.begin(identity.copy(pid = 124), "new-lifetime")
        assertNotEquals(nonce, next)
        assertThrows(IllegalStateException::class.java) { tickets.verify(identity, nonce) }
    }

    @Test fun deathReleasesOnlyItsOwnPendingLaunchAndAllowsImmediateRetry() {
        val nonce = tickets.begin(identity, "lifetime")
        tickets.cancel(identity.copy(pid = 124), "lifetime")
        tickets.cancel(identity, "wrong-lifetime")
        tickets.verify(identity, nonce)
        tickets.cancel(identity, "lifetime")
        val newer = identity.copy(pid = 124)
        val next = tickets.begin(newer, "new-lifetime")
        tickets.cancel(identity, "lifetime")
        tickets.consume(newer, next)
    }
}
