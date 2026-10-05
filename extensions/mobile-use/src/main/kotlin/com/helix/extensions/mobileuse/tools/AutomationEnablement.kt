package com.helix.extensions.mobileuse.tools

import com.helix.extensions.mobileuse.automation.AutomationCapability

/** Availability is per operation; app discovery does not require a screen-control service. */
object AutomationEnablement {
    fun capabilityFor(tool: String?): AutomationCapability =
        when (tool) {
            AutomationDeviceTools.APPS -> AutomationCapability.APPS

            AutomationDeviceTools.LAUNCH -> AutomationCapability.LAUNCH

            AutomationDeviceTools.SYSTEM, AutomationTools.BACK, AutomationTools.HOME -> AutomationCapability.GLOBAL

            AutomationTools.SNAPSHOT, AutomationTools.FIND, AutomationTools.WAIT -> AutomationCapability.SNAPSHOT

            AutomationTools.CLICK, AutomationTools.LONG_CLICK, AutomationTools.SET_TEXT,
            AutomationTools.IME_ENTER, AutomationTools.SET_PROGRESS, AutomationTools.SCROLL,
            AutomationTools.CLICK_MATCH,
            -> AutomationCapability.NODE_ACTION

            AutomationDeviceTools.SCREENSHOT -> AutomationCapability.SCREENSHOT

            AutomationDeviceTools.GESTURE -> AutomationCapability.GESTURE

            else -> AutomationCapability.OBSERVE
        }
}
