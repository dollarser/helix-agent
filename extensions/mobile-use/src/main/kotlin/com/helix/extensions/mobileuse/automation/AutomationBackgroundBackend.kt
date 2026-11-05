package com.helix.extensions.mobileuse.automation

/** An explicit background route never falls back to another backend or the main display. */
internal class AutomationBackgroundBackend(
    private val shell: AutomationPrivilegedBackend?,
    private val route: AutomationBackgroundRoute,
) : AutomationPrivilegedBackend {
    override fun state(): AutomationBackendState =
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            shell?.state() ?: AutomationBackendState.UNAVAILABLE
        } else {
            AutomationBackendState.UNAVAILABLE
        }

    override val deviceOperations get() =
        if (android.os.Build.VERSION.SDK_INT >= 31) AutomationDeviceOperation.entries.toSet() else emptySet()

    @get:androidx.annotation.ChecksSdkIntAtLeast(api = 31)
    override val supportsScopedCapture get() = android.os.Build.VERSION.SDK_INT >= 31

    override fun supportsGlobalAction(action: AutomationGlobalAction) = action == AutomationGlobalAction.BACK

    override fun device(
        request: AutomationDeviceRequest,
        allowed: () -> Boolean,
    ): AutomationDeviceReply {
        if (state() != AutomationBackendState.READY) {
            return AutomationDeviceReply(
                "BACKGROUND_DISPLAY_UNAVAILABLE",
                action = AutomationActionResult(AutomationActionStatus.BACKGROUND_DISPLAY_UNAVAILABLE),
            )
        }
        return requireNotNull(shell).device(request.copy(background = route), allowed)
    }

    override fun click(
        selector: AutomationPrivilegedSelector,
        allowed: (Int, Int, Int) -> Boolean,
        mayFinish: () -> Boolean,
    ) = AutomationActionResult(AutomationActionStatus.ACTION_NOT_DISPATCHED)
}
