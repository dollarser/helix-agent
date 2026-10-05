package com.helix.extensions.mobileuse.automation.backend

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import com.helix.extensions.mobileuse.automation.AutomationDeviceRequest
import com.helix.extensions.mobileuse.automation.privilegedGesturePermitted

/** Keep physical ownership through cleanup; cancellation never releases a still-held touch. */
internal class PrivilegedGestureExecution(
    private val screen: ShizukuHierarchy,
    private val allowed: () -> Boolean,
) {
    fun execute(request: AutomationDeviceRequest): String =
        PrivilegedGestureRunner(
            now = SystemClock::uptimeMillis,
            sleep = SystemClock::sleep,
            allowed = allowed,
            ready = { entered ->
                val expected = if (entered) request.copy(target = request.target?.copy(revision = "")) else request
                privilegedGesturePermitted(expected, screen.deviceTarget(includeRevision = !entered), screen.windows())
            },
            inject = { step, downTime -> inject(step.action, step.touches, downTime) },
        ).execute(privilegedGesturePlan(request.strokes))

    private fun inject(
        action: Int,
        touches: List<PrivilegedTouch>,
        downTime: Long,
    ): Boolean {
        val properties =
            touches.map {
                MotionEvent.PointerProperties().apply {
                    id = it.id
                    toolType =
                        MotionEvent.TOOL_TYPE_FINGER
                }
            }
        val coordinates =
            touches.map {
                MotionEvent.PointerCoords().apply {
                    x = it.point.x
                    y = it.point.y
                    pressure =
                        1f
                    size = 1f
                }
            }
        val event =
            MotionEvent.obtain(
                downTime,
                SystemClock.uptimeMillis(),
                action,
                touches.size,
                properties.toTypedArray(),
                coordinates.toTypedArray(),
                0,
                0,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0,
            )
        return try {
            screen.inject(event)
        } finally {
            event.recycle()
        }
    }
}
