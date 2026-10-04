package com.helix.tools.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationEnablementTest {
    @Test fun noAvailableOperationCannotEnableOrPersist() {
        assertFalse(AutomationEnablement.authorize(emptySet()) { error("must not persist") })
        assertFalse(AutomationEnablement.isEnabled(true, emptySet()))
    }

    @Test fun partialAvailabilityStillRequiresExplicitGrant() {
        val apps = setOf(AutomationDeviceTools.APPS)
        assertFalse(AutomationEnablement.isEnabled(false, apps))
        var saved = false
        assertTrue(AutomationEnablement.authorize(apps) { saved = true })
        assertTrue(AutomationEnablement.isEnabled(saved, apps))
        AutomationServiceState.entries.forEach { service ->
            assertTrue(AutomationEnablement.supports(AutomationDeviceTools.APPS, service))
            listOf(AutomationDeviceTools.GESTURE, AutomationDeviceTools.SCREENSHOT, AutomationDeviceTools.LAUNCH)
                .forEach { tool ->
                    if (service == AutomationServiceState.CONNECTED) {
                        assertTrue(AutomationEnablement.supports(tool, service))
                    } else {
                        assertFalse(AutomationEnablement.supports(tool, service))
                    }
                }
        }
    }

    @Test fun privilegedClickIsIndependentButDoesNotUnlockOtherScreenTools() {
        for (root in AutomationBackendState.entries) {
            for (shizuku in AutomationBackendState.entries) {
                val ready = root == AutomationBackendState.READY || shizuku == AutomationBackendState.READY
                org.junit.Assert.assertEquals(
                    ready,
                    AutomationEnablement.supports(
                        AutomationTools.CLICK_MATCH,
                        AutomationServiceState.DISABLED,
                        root,
                        shizuku,
                    ),
                )
                assertFalse(
                    AutomationEnablement.supports(
                        AutomationDeviceTools.SCREENSHOT,
                        AutomationServiceState.DISABLED,
                        root,
                        shizuku,
                    ),
                )
            }
        }
    }

    @Test fun independentDeviceCapabilityExposesSemanticsButNotUnsupportedLaunch() {
        for (tool in listOf(
            AutomationDeviceTools.DEVICE,
            AutomationDeviceTools.GESTURE,
            AutomationDeviceTools.SCREENSHOT,
            AutomationTools.SNAPSHOT,
            AutomationTools.FIND,
            AutomationTools.CLICK,
            AutomationTools.SET_TEXT,
        )) {
            assertTrue(AutomationEnablement.supports(tool, AutomationServiceState.DISABLED, deviceAvailable = true))
            assertFalse(AutomationEnablement.supports(tool, AutomationServiceState.DISABLED, deviceAvailable = false))
        }
        assertFalse(
            AutomationEnablement.supports(
                AutomationDeviceTools.LAUNCH,
                AutomationServiceState.DISABLED,
                deviceAvailable = true,
            ),
        )
    }

    @Test fun persistenceFailureNeverReportsEnabled() {
        assertThrows(IllegalStateException::class.java) {
            AutomationEnablement.authorize(setOf(AutomationDeviceTools.APPS)) { error("write failed") }
        }
    }
}
