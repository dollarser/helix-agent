package com.helix.tools.automation

import android.accessibilityservice.AccessibilityService
import android.annotation.TargetApi
import android.graphics.Bitmap
import android.os.Build
import com.helix.core.model.VisionLimits
import com.helix.tools.framework.ExecutableToolCall
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant

/**
 * PNG acquisition only; publication and disclosure belong to the host.
 * This SDK-only module uses SDK annotations. The entry point checks API 30 before private capture helpers.
 */
@android.annotation.SuppressLint("UseRequiresApi")
internal class AutomationScreenshotCapture(
    private val service: HelixAccessibilityService,
) {
    private val inFlight = AutomationPhysicalSlot()

    @Suppress("ReturnCount") // Unsupported platform, scope and cancellation are separate diagnostics.
    fun capture(
        frame: AutomationFrame,
        session: ActiveAutomationSession,
        call: ExecutableToolCall,
        currentTarget: () -> AutomationDisplayTarget?,
    ): AutomationScreenshot {
        if (Build.VERSION.SDK_INT < 30) return AutomationScreenshot("SCREENSHOT_REQUIRES_API_30")
        if (!(session.scope.allApplications && session.scope.deniedPackages.isEmpty()) && Build.VERSION.SDK_INT < 34) {
            return AutomationScreenshot("WINDOW_CAPTURE_REQUIRES_API_34_OR_WHOLE_PHONE_GRANT")
        }
        if (call.cancel.isCancelled() ||
            !Instant.now().isBefore(call.deadline)
        ) {
            return AutomationScreenshot("CANCELLED")
        }
        val ticket = inFlight.acquire() ?: return AutomationScreenshot("CAPTURE_IN_PROGRESS")
        val presentation = service.runtimePresentation
        val hidden = presentation?.hideForOperation(call)
        if (presentation != null && hidden == null) {
            inFlight.release(ticket)
            return AutomationScreenshot("OVERLAY_NOT_READY")
        }
        return try {
            captureSupported(frame, session, call, currentTarget, ticket)
        } finally {
            hidden?.close()
        }
    }

    @Suppress("ReturnCount") // Buffer and callback ownership stay in one try/finally.
    @TargetApi(30)
    private fun captureSupported(
        frame: AutomationFrame,
        session: ActiveAutomationSession,
        call: ExecutableToolCall,
        currentTarget: () -> AutomationDisplayTarget?,
        ticket: Any,
    ): AutomationScreenshot {
        val wholeDisplay = session.scope.allApplications && session.scope.deniedPackages.isEmpty()
        val pending =
            PendingAutomationResult<CaptureResult> { result ->
                result.image?.hardwareBuffer?.close()
                inFlight.release(ticket)
            }
        val callback =
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    pending.complete(CaptureResult(result, null))
                }

                override fun onFailure(errorCode: Int) {
                    inFlight.release(ticket)
                    pending.complete(CaptureResult(null, "PLATFORM_SCREENSHOT_ERROR_$errorCode"))
                }
            }
        var entered = false
        try {
            val started =
                AutomationServiceController.withDeviceLease(session.id, mutation = false) { _, _ ->
                    if (currentTarget() != frame.target || call.cancel.isCancelled() ||
                        !Instant.now().isBefore(call.deadline)
                    ) {
                        false
                    } else {
                        entered = true
                        if (Build.VERSION.SDK_INT >= 34 && !wholeDisplay) {
                            service.takeScreenshotOfWindow(frame.target.windowId, service.mainExecutor, callback)
                        } else {
                            service.takeScreenshot(frame.target.displayId, service.mainExecutor, callback)
                        }
                        true
                    }
                } == true
            if (!started) {
                inFlight.release(ticket)
                return AutomationScreenshot("TARGET_OR_AUTHORIZATION_CHANGED")
            }
            val result = pending.await(call) ?: return AutomationScreenshot("CAPTURE_CANCELLED_OR_TIMED_OUT")
            result.error?.let { return AutomationScreenshot(it) }
            val image = requireNotNull(result.image)
            try {
                return encodeCurrentCapture(image, frame, session, call, currentTarget, wholeDisplay)
            } finally {
                image.hardwareBuffer.close()
                inFlight.release(ticket)
            }
        } catch (_: RuntimeException) {
            // Entry without a known callback is not proof that physical capture has ended.
            if (!entered) inFlight.release(ticket)
            return AutomationScreenshot("CAPTURE_FAILED")
        } catch (_: IOException) {
            inFlight.release(ticket)
            return AutomationScreenshot("CAPTURE_EXCEEDS_IMAGE_BUDGET")
        } finally {
            pending.abandon()
        }
    }

    @TargetApi(30)
    private fun encodeCurrentCapture(
        image: AccessibilityService.ScreenshotResult,
        frame: AutomationFrame,
        session: ActiveAutomationSession,
        call: ExecutableToolCall,
        currentTarget: () -> AutomationDisplayTarget?,
        wholeDisplay: Boolean,
    ): AutomationScreenshot {
        if (!captureStillCurrent(frame, session, call, currentTarget)) {
            return AutomationScreenshot("TARGET_OR_AUTHORIZATION_CHANGED")
        }
        val bounds =
            if (wholeDisplay) {
                AutomationNodeBounds(0, 0, frame.target.width, frame.target.height)
            } else {
                frame.target.bounds
            }
        val encoded = encode(image, bounds)
        return if (captureStillCurrent(frame, session, call, currentTarget)) {
            encoded.copy(acquisitionScopeRef = session.scope.toScopeRef())
        } else {
            AutomationScreenshot("TARGET_OR_AUTHORIZATION_CHANGED")
        }
    }

    private fun captureStillCurrent(
        frame: AutomationFrame,
        session: ActiveAutomationSession,
        call: ExecutableToolCall,
        currentTarget: () -> AutomationDisplayTarget?,
    ): Boolean {
        val live = !call.cancel.isCancelled() && Instant.now().isBefore(call.deadline)
        val authorized = AutomationServiceController.deviceLease()?.second?.id == session.id
        return live && authorized && currentTarget() == frame.target
    }

    @Suppress("ReturnCount") // Each conversion owns a bitmap and must release it on early failure.
    @TargetApi(30)
    private fun encode(
        image: AccessibilityService.ScreenshotResult,
        bounds: AutomationNodeBounds,
    ): AutomationScreenshot {
        val buffer = image.hardwareBuffer
        if (buffer.width.toLong() * buffer.height > VisionLimits.MAX_TOTAL_PIXELS) {
            return AutomationScreenshot("CAPTURE_EXCEEDS_IMAGE_BUDGET")
        }
        val hardware =
            Bitmap.wrapHardwareBuffer(buffer, image.colorSpace) ?: return AutomationScreenshot("CAPTURE_FAILED")
        try {
            val bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false) ?: return AutomationScreenshot("CAPTURE_FAILED")
            try {
                val output = LimitedPngBuffer()
                if (!bitmap.compress(
                        Bitmap.CompressFormat.PNG,
                        100,
                        output,
                    )
                ) {
                    return AutomationScreenshot("ENCODING_FAILED")
                }
                return AutomationScreenshot("SAVED", output.toByteArray(), bitmap.width, bitmap.height, bounds)
            } finally {
                bitmap.recycle()
            }
        } finally {
            hardware.recycle()
        }
    }

    @TargetApi(30)
    private data class CaptureResult(
        val image: AccessibilityService.ScreenshotResult?,
        val error: String?,
    )

    private class LimitedPngBuffer : ByteArrayOutputStream() {
        override fun write(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ) {
            if (count.toLong() + length > VisionLimits.MAX_INPUT_BYTES) throw IOException("PNG budget exceeded")
            super.write(buffer, offset, length)
        }

        override fun write(value: Int) {
            if (count.toLong() >= VisionLimits.MAX_INPUT_BYTES) throw IOException("PNG budget exceeded")
            super.write(value)
        }
    }
}
