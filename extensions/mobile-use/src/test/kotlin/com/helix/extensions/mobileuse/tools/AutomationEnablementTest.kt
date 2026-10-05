package com.helix.extensions.mobileuse.tools

import com.helix.extensions.mobileuse.automation.AutomationCapability
import org.junit.Assert.assertEquals
import org.junit.Test

class AutomationEnablementTest {
    @Test fun semanticAndCoordinateToolsRequestTheirOwnOperation() {
        val groups =
            mapOf(
                AutomationCapability.APPS to listOf(AutomationDeviceTools.APPS),
                AutomationCapability.LAUNCH to listOf(AutomationDeviceTools.LAUNCH),
                AutomationCapability.GLOBAL to
                    listOf(AutomationDeviceTools.SYSTEM, AutomationTools.BACK, AutomationTools.HOME),
                AutomationCapability.SNAPSHOT to
                    listOf(AutomationTools.SNAPSHOT, AutomationTools.FIND, AutomationTools.WAIT),
                AutomationCapability.NODE_ACTION to
                    listOf(
                        AutomationTools.CLICK,
                        AutomationTools.CLICK_MATCH,
                        AutomationTools.LONG_CLICK,
                        AutomationTools.SET_TEXT,
                        AutomationTools.IME_ENTER,
                        AutomationTools.SET_PROGRESS,
                        AutomationTools.SCROLL,
                    ),
                AutomationCapability.SCREENSHOT to listOf(AutomationDeviceTools.SCREENSHOT),
                AutomationCapability.GESTURE to listOf(AutomationDeviceTools.GESTURE),
                AutomationCapability.OBSERVE to listOf(AutomationDeviceTools.DEVICE),
            )
        groups.forEach { (capability, tools) ->
            tools.forEach { assertEquals(it, capability, AutomationEnablement.capabilityFor(it)) }
        }
    }
}
