package com.helix.tools.automation

/** Availability is per operation; app discovery does not require a screen-control service. */
object AutomationEnablement {
    fun isEnabled(
        hasGrant: Boolean,
        availableTools: Set<String>,
    ): Boolean = hasGrant && availableTools.isNotEmpty()

    fun supports(
        tool: String?,
        service: AutomationServiceState,
        root: AutomationBackendState = AutomationBackendState.UNAVAILABLE,
        shizuku: AutomationBackendState = AutomationBackendState.UNAVAILABLE,
        deviceAvailable: Boolean = false,
    ): Boolean =
        tool == AutomationDeviceTools.APPS || service == AutomationServiceState.CONNECTED ||
            (
                tool == AutomationTools.CLICK_MATCH &&
                    (root == AutomationBackendState.READY || shizuku == AutomationBackendState.READY)
            ) || (
                deviceAvailable && tool in
                    setOf(
                        AutomationDeviceTools.DEVICE,
                        AutomationDeviceTools.SCREENSHOT,
                        AutomationDeviceTools.GESTURE,
                        AutomationTools.SNAPSHOT,
                        AutomationTools.FIND,
                        AutomationTools.WAIT,
                        AutomationTools.CLICK,
                        AutomationTools.LONG_CLICK,
                        AutomationTools.SET_TEXT,
                        AutomationTools.IME_ENTER,
                        AutomationTools.SET_PROGRESS,
                        AutomationTools.SCROLL,
                    )
            )

    fun authorize(
        availableTools: Set<String>,
        save: () -> Unit,
    ): Boolean {
        if (availableTools.isEmpty()) return false
        save()
        return true
    }
}
