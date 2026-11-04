package com.helix.extensions.mobileuse.automation.backend

import android.os.IBinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuFocusRecoveryTest {
    private class Peer {
        var death: IBinder.DeathRecipient? = null
        var alive = true
        val binder =
            java.lang.reflect.Proxy.newProxyInstance(
                IBinder::class.java.classLoader,
                arrayOf(IBinder::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "linkToDeath" -> {
                        death = args!![0] as IBinder.DeathRecipient
                        null
                    }

                    "unlinkToDeath" -> {
                        true
                    }

                    "isBinderAlive" -> {
                        alive
                    }

                    else -> {
                        error("Unexpected Binder operation: ${method.name}")
                    }
                }
            } as IBinder

        fun die() {
            alive = false
            requireNotNull(death).binderDied()
        }
    }

    @Test fun actualDeathSchedulesOnlyTheOriginalCheckpointOnceWithAFixedDeadline() {
        val work = mutableListOf<() -> Unit>()
        val restored = mutableListOf<Pair<FocusRecoveryCheckpoint, Long>>()
        val peer = Peer()
        val owner = ShizukuFocusRecovery(work::add) { 100 }
        val checkpoint = FocusRecoveryCheckpoint(4, 1, listOf(7))
        owner.bind(peer.binder) { value, deadline -> restored += value to deadline }
        owner.record(checkpoint)
        assertTrue(work.isEmpty())
        peer.die()
        peer.die()
        owner.record(FocusRecoveryCheckpoint(5, 2, listOf(8)))
        assertEquals(1, work.size)
        assertTrue(restored.isEmpty())
        work.single().invoke()
        assertEquals(listOf(checkpoint to 5100L), restored)
    }

    @Test fun confirmedNormalReleaseDisarmsDeathCleanupAndUnobservedTasksGrantNothing() {
        val work = mutableListOf<() -> Unit>()
        val peer = Peer()
        val owner = ShizukuFocusRecovery(work::add) { 0 }
        owner.bind(peer.binder) { _, _ -> error("Must not restore") }
        owner.record(FocusRecoveryCheckpoint(4, 1, listOf(7)))
        owner.released()
        peer.die()
        assertTrue(work.isEmpty())
        val absent = ShizukuFocusRecovery(work::add) { 0 }
        val other = Peer()
        absent.bind(other.binder) { _, _ -> error("No checkpoint") }
        other.die()
        assertTrue(work.isEmpty())
    }

    @Test fun recoveryRefusesChangedMainOrderOtherFocusAndMalformedIdentities() {
        val checkpoint = FocusRecoveryCheckpoint(4, 1, listOf(7, 8))
        assertTrue(checkpoint.canRestore(listOf(8, 7, 1, 2), 8))
        assertFalse(checkpoint.canRestore(listOf(7, 2, 1), 7))
        assertFalse(checkpoint.canRestore(listOf(2, 7, 1), 2))
        assertFalse(checkpoint.canRestore(listOf(7, 2), 7))
        assertFalse(checkpoint.canRestore(listOf(1, 7), null))
        assertThrows(IllegalArgumentException::class.java) { FocusRecoveryCheckpoint(0, 1, listOf(7)) }
        assertThrows(IllegalArgumentException::class.java) { FocusRecoveryCheckpoint(4, 1, listOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { FocusRecoveryCheckpoint(4, 1, listOf(7, 7)) }
        assertThrows(IllegalArgumentException::class.java) { FocusRecoveryCheckpoint(4, 1, (2..34).toList()) }
    }

    @Test fun originalCleanupOwnerCannotBeReboundToAnotherService() {
        val owner = ShizukuFocusRecovery({ error("No death scheduled") }) { 0 }
        val first = Peer()
        owner.bind(first.binder) { _, _ -> }
        assertThrows(IllegalStateException::class.java) {
            owner.bind(Peer().binder) { _, _ -> }
        }
        owner.released()
    }

    @Test fun expiredRevokedLockedAndStillLiveDisplayNeverAdmitFocusCleanup() {
        assertEquals(null, focusCleanupRefusal(1000, true, false, false))
        assertEquals("CLEANUP_EXPIRED", focusCleanupRefusal(-1, true, false, false))
        assertEquals("CLEANUP_EXPIRED", focusCleanupRefusal(5001, true, false, false))
        assertEquals("AUTHORIZATION_LOST", focusCleanupRefusal(1000, false, false, false))
        assertEquals("DEVICE_LOCKED", focusCleanupRefusal(1000, true, true, false))
        assertEquals("ORIGINAL_DISPLAY_STILL_PRESENT", focusCleanupRefusal(1000, true, false, true))
    }
}
