package com.helix.tools.automation

import com.helix.tools.framework.ExecutableToolCall
import java.util.UUID

/** Coordinates are physical display pixels, not density-independent pixels or screenshot-relative coordinates. */
data class AutomationDisplayTarget(
    val packageName: String,
    val windowId: Int,
    val displayId: Int,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val bounds: AutomationNodeBounds,
)

data class AutomationFrame(
    val token: String,
    val grantId: String,
    val target: AutomationDisplayTarget,
)

data class AutomationPoint(
    val x: Float,
    val y: Float,
)

data class AutomationStroke(
    val points: List<AutomationPoint>,
    val startMillis: Long,
    val durationMillis: Long,
)

data class AutomationApp(
    val packageName: String,
    val label: String,
)

data class AutomationAppListing(
    val status: String,
    val apps: List<AutomationApp> = emptyList(),
    val truncated: Boolean = false,
)

data class AutomationWindowRegion(
    val windowId: Int,
    val layer: Int,
    val packageName: String?,
    val bounds: AutomationNodeBounds,
)

data class AutomationDeviceObservation(
    val status: String,
    val frame: AutomationFrame? = null,
    val systemActions: Set<AutomationGlobalAction> = emptySet(),
    val allApplications: Boolean = false,
    val screenshotSupported: Boolean = false,
    val gestureSupported: Boolean = false,
)

data class AutomationScreenshot(
    val status: String,
    val png: ByteArray? = null,
    val width: Int = 0,
    val height: Int = 0,
    val screenBounds: AutomationNodeBounds? = null,
    val acquisitionScopeRef: String? = null,
)

interface AutomationDevicePort {
    fun forCall(call: ExecutableToolCall): AutomationDevicePort = this

    fun observe(): AutomationDeviceObservation

    fun apps(): AutomationAppListing

    fun launch(
        packageName: String,
        call: ExecutableToolCall,
    ): AutomationActionResult

    fun gesture(
        frame: String,
        strokes: List<AutomationStroke>,
        call: ExecutableToolCall,
    ): AutomationActionResult

    fun screenshot(
        frame: String,
        call: ExecutableToolCall,
    ): AutomationScreenshot
}

/** A new observation replaces the old coordinate token. The token cannot survive a replaced user grant. */
internal class AutomationFrameRegistry(
    private val newToken: () -> String = { UUID.randomUUID().toString() },
) {
    private var latest: AutomationFrame? = null

    @Synchronized
    fun issue(
        grantId: String,
        target: AutomationDisplayTarget,
    ): AutomationFrame = AutomationFrame(newToken(), grantId, target).also { latest = it }

    @Synchronized
    fun resolve(
        token: String,
        grantId: String,
        current: AutomationDisplayTarget,
    ): AutomationFrame? = latest?.takeIf { it.token == token && it.grantId == grantId && it.target == current }

    @Synchronized
    fun invalidate() {
        latest = null
    }
}

/** Restricted-app gestures cannot interact with an unapproved overlay or a different split-screen window. */
@Suppress("ReturnCount") // Early rejection for absent window/points precedes overlap checking.
internal fun gestureWindowsPermitted(
    scope: com.helix.core.policy.AutomationSessionScope,
    target: AutomationDisplayTarget,
    strokes: List<AutomationStroke>,
    windows: List<AutomationWindowRegion>,
): Boolean {
    if (scope.allApplications && scope.deniedPackages.isEmpty()) return true
    val active = windows.firstOrNull { it.windowId == target.windowId } ?: return false
    val points = strokes.flatMap { it.points }
    if (points.isEmpty()) return false
    val left = points.minOf { it.x }
    val right = points.maxOf { it.x }
    val top = points.minOf { it.y }
    val bottom = points.maxOf { it.y }
    return windows.none { window ->
        window.windowId != target.windowId && window.layer >= active.layer &&
            window.bounds.left <= right && window.bounds.right > left &&
            window.bounds.top <= bottom && window.bounds.bottom > top &&
            (window.packageName == null || !scope.permitsPackage(window.packageName))
    }
}

internal fun validateAutomationGesture(
    strokes: List<AutomationStroke>,
    target: AutomationDisplayTarget,
    allApplications: Boolean,
    maxStrokes: Int,
    maxDuration: Long,
) {
    require(strokes.isNotEmpty() && strokes.size <= maxStrokes) { "GESTURE_STROKES_INVALID" }
    val permitted = if (allApplications) AutomationNodeBounds(0, 0, target.width, target.height) else target.bounds
    strokes.forEach { stroke ->
        require(stroke.points.isNotEmpty() && stroke.points.size <= 512) { "GESTURE_POINTS_INVALID" }
        require(stroke.startMillis >= 0 && stroke.durationMillis in 1..maxDuration) { "GESTURE_TIME_INVALID" }
        require(stroke.startMillis <= maxDuration - stroke.durationMillis) { "GESTURE_TIME_INVALID" }
        stroke.points.forEach { point ->
            require(point.x.isFinite() && point.y.isFinite()) { "GESTURE_COORDINATE_INVALID" }
            require(point.x >= 0 && point.y >= 0 && point.x < target.width && point.y < target.height) {
                "GESTURE_OUTSIDE_DISPLAY"
            }
            require(
                point.x >= permitted.left && point.x < permitted.right &&
                    point.y >= permitted.top && point.y < permitted.bottom,
            ) { "GESTURE_OUTSIDE_AUTHORIZED_WINDOW" }
        }
    }
}
