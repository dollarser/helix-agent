package com.helix.extensions.mobileuse.automation

/** Host capabilities carry no model-facing names or plugin registration policy. */
enum class AutomationCapability(
    val operation: AutomationDeviceOperation? = null,
) {
    APPS,
    LAUNCH(AutomationDeviceOperation.LAUNCH),
    GLOBAL(AutomationDeviceOperation.GLOBAL_ACTION),
    OBSERVE(AutomationDeviceOperation.OBSERVE),
    SNAPSHOT(AutomationDeviceOperation.SNAPSHOT),
    NODE_ACTION(AutomationDeviceOperation.NODE_ACTION),
    SCREENSHOT(AutomationDeviceOperation.SCREENSHOT),
    GESTURE(AutomationDeviceOperation.GESTURE),
}
