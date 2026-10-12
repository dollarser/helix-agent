package com.helix.extensions.mobileuse.automation

/** Retry only discarded observations, under the same original admission and physical lease. */
@Suppress("ReturnCount") // Preserve distinct admission failure and unmodified capture outcomes.
internal fun readStableAccessibilitySnapshot(
    capture: () -> AutomationSnapshotResult,
    generation: () -> Long,
    admitted: () -> Boolean,
    invalidate: () -> Unit,
): AutomationSnapshotResult {
    var refused = AutomationSnapshotResult(AutomationSnapshotStatus.TARGET_CHANGED)
    repeat(3) {
        if (!admitted()) return AutomationSnapshotResult(AutomationSnapshotStatus.NO_ACTIVE_SESSION)
        val result = capture()
        if (!admitted()) {
            invalidate()
            return AutomationSnapshotResult(AutomationSnapshotStatus.NO_ACTIVE_SESSION)
        }
        val observed = result.snapshot ?: return result
        if (observed.generation == generation()) return result
        invalidate()
        refused =
            AutomationSnapshotResult(AutomationSnapshotStatus.TARGET_CHANGED, targetPackage = observed.packageName)
    }
    return refused
}
