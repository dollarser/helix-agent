package com.helix.extensions.mobileuse.automation.backend

import com.helix.extensions.mobileuse.automation.AutomationDisplayLifecycle
import com.helix.extensions.mobileuse.automation.AutomationDisplayPresentation
import org.junit.Assert.assertEquals
import org.junit.Test

class ShizukuDisplayLifecycleTest {
    @Test fun releaseConfirmationIsTerminalAndLateCallbacksCannotResurrectTheDisplay() {
        val events = mutableListOf<AutomationDisplayPresentation>()
        var finished = 0
        val owner = ShizukuDisplayLifecycle("session", "turn", 7, events::add, { true }) { finished++ }
        owner.report(AutomationDisplayLifecycle.ACTIVE)
        owner.observedPackage("com.example.fixture")
        owner.report(AutomationDisplayLifecycle.RELEASED)
        owner.report(AutomationDisplayLifecycle.ACTIVE)
        owner.report(AutomationDisplayLifecycle.CONNECTION_LOST)
        owner.observedPackage("com.wrong.target")
        assertEquals(AutomationDisplayLifecycle.RELEASED, events.last().lifecycle)
        assertEquals("com.example.fixture", events.last().lastObservedPackage)
        assertEquals(7L, events.last().generation)
        assertEquals(1, finished)
        assertEquals(3, events.size)
    }

    @Test fun registeredBinderDeathNeedsSeparateReleaseConfirmation() {
        val events = mutableListOf<AutomationDisplayPresentation>()
        var death: android.os.IBinder.DeathRecipient? = null
        var alive = true
        val binder =
            java.lang.reflect.Proxy.newProxyInstance(
                android.os.IBinder::class.java.classLoader,
                arrayOf(android.os.IBinder::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "linkToDeath" -> {
                        death = args!![0] as android.os.IBinder.DeathRecipient
                        null
                    }

                    "unlinkToDeath" -> {
                        false
                    }

                    "isBinderAlive" -> {
                        alive
                    }

                    else -> {
                        error("Unexpected Binder operation: ${method.name}")
                    }
                }
            } as android.os.IBinder
        val owner = ShizukuDisplayLifecycle("s", "t", 1, events::add, { true }) {}
        owner.bind(binder)
        assertEquals(null, owner.previewService("s", "t", 1))
        owner.report(AutomationDisplayLifecycle.ACTIVE)
        org.junit.Assert.assertSame(binder, owner.previewService("s", "t", 1))
        assertEquals(null, owner.previewService("other", "t", 1))
        assertEquals(null, owner.previewService("s", "other", 1))
        assertEquals(null, owner.previewService("s", "t", 2))
        alive = false
        requireNotNull(death).binderDied()
        assertEquals(AutomationDisplayLifecycle.CONNECTION_LOST, events.last().lifecycle)
        assertEquals(null, owner.previewService("s", "t", 1))
        owner.report(AutomationDisplayLifecycle.ACTIVE)
        assertEquals(AutomationDisplayLifecycle.CONNECTION_LOST, events.last().lifecycle)
        owner.report(AutomationDisplayLifecycle.RELEASED)
        assertEquals(AutomationDisplayLifecycle.RELEASED, events.last().lifecycle)
    }

    @Test fun targetMetadataRequiresCurrentOriginalScopeAndIsRemovedAfterRevocation() {
        val events = mutableListOf<AutomationDisplayPresentation>()
        var permitted = false
        val owner = ShizukuDisplayLifecycle("s", "t", 1, events::add, { permitted }) {}
        owner.report(AutomationDisplayLifecycle.ACTIVE)
        owner.observedPackage("com.example.fixture")
        assertEquals(null, events.last().lastObservedPackage)
        permitted = true
        owner.observedPackage("com.example.fixture")
        assertEquals("com.example.fixture", events.last().lastObservedPackage)
        permitted = false
        owner.report(AutomationDisplayLifecycle.RELEASED)
        assertEquals(null, events.last().lastObservedPackage)
    }

    @Test fun lostConnectionOrUnconfirmedReplyNeverClaimsRelease() {
        val events = mutableListOf<AutomationDisplayPresentation>()
        val owner = ShizukuDisplayLifecycle("s", "t", 1, events::add, { true }) {}
        owner.report(AutomationDisplayLifecycle.ACTIVE)
        owner.report(AutomationDisplayLifecycle.CONNECTION_LOST)
        assertEquals(AutomationDisplayLifecycle.CONNECTION_LOST, events.last().lifecycle)
        val pending = ShizukuDisplayLifecycle("s", "other", 2, events::add, { true }) {}
        pending.refused("PRIVILEGED_DEVICE_FAILED")
        assertEquals(AutomationDisplayLifecycle.UNKNOWN, events.last().lifecycle)
    }
}
