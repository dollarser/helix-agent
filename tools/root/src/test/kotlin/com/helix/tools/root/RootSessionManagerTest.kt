package com.helix.tools.root

import com.helix.core.model.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RootSessionManagerTest {
    private val clock = MutableClock(Instant.parse("2026-09-05T00:00:00Z"))
    private var access = connected()
    private var closeCount = 0
    private val manager = RootSessionManager(clock, { access }) { closeCount += 1 }

    @Test
    fun startRequiresLiveGrantAndRootService() {
        access = RootAccessStatus(RootGrantState.DENIED, RootServiceState.DISCONNECTED)
        assertThrows(IllegalArgumentException::class.java) { manager.start() }
        assertEquals(RootSessionState.INACTIVE, manager.status().state)
    }

    @Test
    fun tenMinuteIdleDeadlineSlidesButHardCapDoesNot() {
        val initial = manager.start(mapOf("system" to "/system/etc"))
        assertEquals(clock.now().plusSeconds(600), initial.scope?.expiresAt)
        clock.advanceSeconds(590)
        manager.recordSuccessfulActivity()
        assertEquals(clock.now().plusSeconds(600), manager.status().scope?.expiresAt)

        clock.advanceSeconds(60 * 50)
        assertEquals(RootSessionState.EXPIRED, manager.status().state)
        assertEquals(1, closeCount)
        assertNull(manager.status().scope)
    }

    @Test
    fun inactivityClockRollbackAndGrantLossFailClosed() {
        manager.start()
        clock.advanceSeconds(600)
        assertEquals(RootSessionState.EXPIRED, manager.status().state)

        manager.start()
        clock.advanceSeconds(-1)
        assertEquals(RootSessionState.EXPIRED, manager.status().state)

        clock.advanceSeconds(1)
        manager.start()
        access = RootAccessStatus(RootGrantState.LOST, RootServiceState.DISCONNECTED)
        assertEquals(RootSessionState.LOST, manager.status().state)
        assertEquals(3, closeCount)
    }

    @Test
    fun pathResolutionIsBoundToSelectedRootAndRejectsCredentialAreas() {
        manager.start(mapOf("system" to "/system/etc"))
        assertEquals(
            ResolvedRootPath("/system/etc", "/system/etc/hosts"),
            manager.resolve("system", "hosts"),
        )
        assertThrows(IllegalArgumentException::class.java) { manager.resolve("system", "../../data/user/0/x") }
        assertThrows(IllegalArgumentException::class.java) { manager.resolve("missing", "hosts") }
        assertThrows(IllegalArgumentException::class.java) { manager.start(mapOf("private" to "/data/user/0/app")) }
        assertThrows(IllegalArgumentException::class.java) { manager.start(mapOf("all" to "/")) }
    }

    @Test
    fun userCloseImmediatelyDropsRootsAndDisconnects() {
        manager.start(mapOf("system" to "/system/etc"))
        manager.close()
        assertEquals(RootSessionState.INACTIVE, manager.status().state)
        assertTrue(manager.status().rootScopeIds.isEmpty())
        assertEquals(1, closeCount)
    }

    private fun connected() = RootAccessStatus(RootGrantState.GRANTED, RootServiceState.CONNECTED)
}

private class MutableClock(
    private var instant: Instant,
) : Clock {
    override fun now(): Instant = instant

    fun advanceSeconds(seconds: Long) {
        instant = instant.plusSeconds(seconds)
    }
}
