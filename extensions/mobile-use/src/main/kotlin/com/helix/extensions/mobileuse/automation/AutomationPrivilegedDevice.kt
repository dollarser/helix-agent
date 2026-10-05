package com.helix.extensions.mobileuse.automation

/** Closed platform operations; never accepts shell commands or caller-supplied image paths. */
enum class AutomationDeviceOperation { OBSERVE, SCREENSHOT, GESTURE, SNAPSHOT, NODE_ACTION }

data class AutomationDeviceRequest(
    val operation: AutomationDeviceOperation,
    val target: AutomationDisplayTarget? = null,
    val strokes: List<AutomationStroke> = emptyList(),
    val wholeDisplay: Boolean = false,
    val nodeAction: AutomationNodeActionRequest? = null,
    val nodeFingerprint: String? = null,
)

data class AutomationDeviceReply(
    val status: String,
    val target: AutomationDisplayTarget? = null,
    val screenshot: AutomationScreenshot? = null,
    val action: AutomationActionResult? = null,
    val snapshot: AutomationSnapshotResult? = null,
)

fun AutomationDeviceOperation.hasEffect(): Boolean =
    this == AutomationDeviceOperation.GESTURE || this == AutomationDeviceOperation.NODE_ACTION

/** Shared host/remote validation. Restricted gestures cannot cross an unrelated covering window. */
@Suppress("ReturnCount") // Distinct absence, changed frame and whole-display boundaries fail closed.
fun privilegedGesturePermitted(
    request: AutomationDeviceRequest,
    current: AutomationDisplayTarget?,
    windows: List<AutomationWindowRegion>,
): Boolean {
    val target = requireNotNull(request.target)
    validateAutomationGesture(request.strokes, target, request.wholeDisplay, 10, 10_000)
    if (current != target) return false
    val active = windows.singleOrNull { it.windowId == target.windowId } ?: return false
    if (request.wholeDisplay) return true
    val points = request.strokes.flatMap { it.points }
    val left = points.minOf { it.x }
    val right = points.maxOf { it.x }
    val top = points.minOf { it.y }
    val bottom = points.maxOf { it.y }
    return windows.none {
        it.windowId != active.windowId && it.layer >= active.layer &&
            it.bounds.left <= right && it.bounds.right > left && it.bounds.top <= bottom && it.bounds.bottom > top
    }
}

/** Node actions bind their own semantics and geometry, not unrelated animated page content. */
fun AutomationSnapshotNode.privilegedFingerprint(): String =
    nodeFingerprint(
        listOf(
            depth.toString(),
            className,
            text,
            contentDescription,
            viewId,
            bounds.toString(),
            clickable.toString(),
            longClickable.toString(),
            editable.toString(),
            scrollable.toString(),
            enabled.toString(),
            range.toString(),
            canSetProgress.toString(),
            redacted.toString(),
            canImeEnter.toString(),
            checkable.toString(),
            checked.toString(),
        ),
    )

fun AutomationDisplayTarget.sameWindow(other: AutomationDisplayTarget?): Boolean =
    other != null && copy(revision = "") == other.copy(revision = "")

fun privilegedSemanticNodeMatches(
    request: AutomationDeviceRequest,
    current: AutomationDisplayTarget?,
    node: AutomationSnapshotNode,
): Boolean =
    request.operation == AutomationDeviceOperation.NODE_ACTION &&
        request.target?.sameWindow(current) == true && request.nodeAction?.token == node.token &&
        request.nodeFingerprint == node.privilegedFingerprint()
