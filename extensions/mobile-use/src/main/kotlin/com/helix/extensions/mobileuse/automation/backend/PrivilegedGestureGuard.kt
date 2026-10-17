package com.helix.extensions.mobileuse.automation.backend

import com.helix.extensions.mobileuse.automation.AutomationDeviceRequest
import com.helix.extensions.mobileuse.automation.AutomationDisplayTarget
import com.helix.extensions.mobileuse.automation.AutomationWindowRegion
import com.helix.extensions.mobileuse.automation.privilegedGesturePermitted

/** Physical coordinates survive a window transition, but never a changed display mapping. */
internal data class GestureDisplay(
    val id: Int,
    val width: Int,
    val height: Int,
    val rotation: Int,
)

internal fun AutomationDisplayTarget.gestureDisplay(): GestureDisplay =
    GestureDisplay(displayId, width, height, rotation)

/** Authorization/cancellation is checked separately by the runner for every input event. */
internal class PrivilegedGestureGuard(
    private val request: AutomationDeviceRequest,
    private val target: (Boolean) -> AutomationDisplayTarget?,
    private val display: () -> GestureDisplay,
    private val windows: () -> List<AutomationWindowRegion>,
) {
    fun ready(entered: Boolean): Boolean =
        when {
            !entered -> privilegedGesturePermitted(request, target(true), windows())

            // A shade/panel may replace the active root while this same authorized touch is held.
            request.wholeDisplay -> requireNotNull(request.target).gestureDisplay() == display()

            else -> withinApp()
        }

    private fun withinApp(): Boolean {
        val original = requireNotNull(request.target)
        val current = target(false) ?: return false
        // A new window in the authorized app is valid only while the path still lies in that window
        // and no other window covers it. An absent root cannot establish a restricted scope.
        return original.packageName == current.packageName && original.gestureDisplay() == current.gestureDisplay() &&
            privilegedGesturePermitted(request.copy(target = current), current, windows())
    }
}
