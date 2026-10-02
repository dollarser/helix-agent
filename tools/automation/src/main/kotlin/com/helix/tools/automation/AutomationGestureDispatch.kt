package com.helix.tools.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.helix.tools.framework.ExecutableToolCall
import java.time.Instant

/** One physical gesture at a time; release only on the actual Android callback, not a watchdog timeout. */
internal class AutomationGestureDispatch(
    private val service: HelixAccessibilityService,
) {
    private val inFlight = AutomationPhysicalSlot()

    @Suppress("ReturnCount") // Pre-dispatch refusals and uncertain post-dispatch outcomes are distinct.
    fun execute(
        frame: AutomationFrame,
        session: ActiveAutomationSession,
        strokes: List<AutomationStroke>,
        call: ExecutableToolCall,
        currentTarget: () -> AutomationDisplayTarget?,
        permittedWindows: () -> Boolean,
        dispatched: () -> Unit,
    ): AutomationActionResult {
        validateAutomationGesture(
            strokes,
            frame.target,
            session.scope.allApplications && session.scope.deniedPackages.isEmpty(),
            GestureDescription.getMaxStrokeCount(),
            GestureDescription.getMaxGestureDuration(),
        )
        val gesture = build(frame.target.displayId, strokes)
        if (!callIsActive(call)) return notDispatched()
        val ticket = inFlight.acquire() ?: return notDispatched()
        val pending = PendingAutomationResult<AutomationActionResult>()
        val callback =
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    inFlight.release(ticket)
                    pending.complete(AutomationActionResult(AutomationActionStatus.SUCCEEDED))
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    inFlight.release(ticket)
                    pending.complete(AutomationActionResult(AutomationActionStatus.ACTION_OUTCOME_UNKNOWN))
                }
            }
        var entered = false
        try {
            val accepted =
                AutomationServiceController.withDeviceLease(session.id, mutation = true) { _, current ->
                    val liveCall = callIsActive(call)
                    val sameTarget =
                        currentTarget() == frame.target &&
                            current.scope.permitsPackage(frame.target.packageName)
                    if (!liveCall || !sameTarget || !permittedWindows()) {
                        false
                    } else {
                        entered = true
                        dispatched()
                        service.dispatchGesture(gesture, callback, Handler(Looper.getMainLooper()))
                    }
                } == true
            if (!accepted) {
                inFlight.release(ticket)
                return notDispatched()
            }
            // Android cancellation may happen after partial motion. Never silently replay it.
            return pending.await(call) ?: AutomationActionResult(AutomationActionStatus.ACTION_OUTCOME_UNKNOWN)
        } catch (_: RuntimeException) {
            if (!entered) inFlight.release(ticket)
            return AutomationActionResult(
                if (entered) {
                    AutomationActionStatus.ACTION_OUTCOME_UNKNOWN
                } else {
                    AutomationActionStatus.ACTION_NOT_DISPATCHED
                },
            )
        } finally {
            pending.abandon()
        }
    }

    private fun build(
        displayId: Int,
        strokes: List<AutomationStroke>,
    ): GestureDescription {
        val builder = GestureDescription.Builder()
        if (Build.VERSION.SDK_INT >= 30) builder.setDisplayId(displayId)
        strokes.forEach { stroke ->
            val path = Path()
            path.moveTo(stroke.points.first().x, stroke.points.first().y)
            stroke.points.drop(1).forEach { path.lineTo(it.x, it.y) }
            builder.addStroke(GestureDescription.StrokeDescription(path, stroke.startMillis, stroke.durationMillis))
        }
        return builder.build()
    }

    private fun callIsActive(call: ExecutableToolCall): Boolean =
        !call.cancel.isCancelled() && Instant.now().isBefore(call.deadline)

    private fun notDispatched() = AutomationActionResult(AutomationActionStatus.ACTION_NOT_DISPATCHED)
}
