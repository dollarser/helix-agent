package com.helix.extensions.mobileuse.automation.backend

import android.content.Context
import com.helix.extensions.mobileuse.automation.AutomationActionResult
import com.helix.extensions.mobileuse.automation.AutomationActionStatus
import com.helix.extensions.mobileuse.automation.AutomationBackendState
import com.helix.extensions.mobileuse.automation.AutomationPrivilegedBackend
import com.helix.extensions.mobileuse.automation.AutomationPrivilegedSelector
import com.helix.extensions.mobileuse.automation.hasEffect
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicBoolean

class ShizukuAutomationBackend(
    context: Context,
) : AutomationPrivilegedBackend {
    private val bridge = ShizukuUiBridge(context.applicationContext)

    override fun state(): AutomationBackendState =
        when {
            !bridge.binderReady() -> if (seen.get()) AutomationBackendState.LOST else AutomationBackendState.UNAVAILABLE
            !bridge.permissionGranted() -> AutomationBackendState.PERMISSION_REQUIRED.also { seen.set(true) }
            else -> AutomationBackendState.READY.also { seen.set(true) }
        }

    override val deviceOperations =
        com.helix.extensions.mobileuse.automation.AutomationDeviceOperation.entries
            .toSet()

    @Suppress("TooGenericExceptionCaught") // Binding failure does not justify replaying a possibly dispatched gesture.
    override fun device(
        request: com.helix.extensions.mobileuse.automation.AutomationDeviceRequest,
        allowed: () -> Boolean,
    ): com.helix.extensions.mobileuse.automation.AutomationDeviceReply =
        try {
            bridge.device(request, allowed)
        } catch (_: Exception) {
            com.helix.extensions.mobileuse.automation.AutomationDeviceReply(
                "SHIZUKU_DEVICE_UNAVAILABLE",
                action =
                    if (request.operation.hasEffect()) {
                        AutomationActionResult(AutomationActionStatus.ACTION_OUTCOME_UNKNOWN)
                    } else {
                        null
                    },
            )
        }

    @Suppress("TooGenericExceptionCaught") // Transport/parser exceptions after IPC all require effect review.
    override fun click(
        selector: AutomationPrivilegedSelector,
        allowed: (Int, Int, Int) -> Boolean,
        mayFinish: () -> Boolean,
    ): AutomationActionResult {
        val status = state()
        if (status != AutomationBackendState.READY) {
            return AutomationActionResult(
                if (status == AutomationBackendState.PERMISSION_REQUIRED) {
                    AutomationActionStatus.SHIZUKU_PERMISSION_REQUIRED
                } else {
                    AutomationActionStatus.SHIZUKU_UNAVAILABLE
                },
            )
        }
        return try {
            val result =
                bridge.clickMatch(
                    ShizukuUiSelector(selector.packageName, selector.viewId, selector.text),
                    allowed,
                    mayFinish,
                )
            AutomationActionResult(shizukuActionStatus(result.result["status"]?.jsonPrimitive?.content))
        } catch (error: Exception) {
            android.util.Log.w("HelixShizuku", "Backend failure: ${error.javaClass.simpleName}")
            // Binder loss/timeout may follow an injection; no retry and no rollback claim.
            AutomationActionResult(AutomationActionStatus.ACTION_OUTCOME_UNKNOWN)
        }
    }

    private companion object {
        val seen = AtomicBoolean(false)
    }
}

internal fun shizukuActionStatus(status: String?): AutomationActionStatus =
    when (status) {
        "DISPATCHED" -> AutomationActionStatus.SUCCEEDED
        "TARGET_NOT_FOUND" -> AutomationActionStatus.TARGET_NOT_FOUND
        "TARGET_AMBIGUOUS" -> AutomationActionStatus.TARGET_AMBIGUOUS
        "TARGET_CHANGED" -> AutomationActionStatus.TARGET_CHANGED
        "NOT_DISPATCHED" -> AutomationActionStatus.ACTION_NOT_DISPATCHED
        else -> AutomationActionStatus.ACTION_OUTCOME_UNKNOWN
    }
