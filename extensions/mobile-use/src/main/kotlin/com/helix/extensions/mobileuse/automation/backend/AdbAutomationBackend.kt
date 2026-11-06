package com.helix.extensions.mobileuse.automation.backend

import android.os.IBinder
import com.helix.extensions.mobileuse.automation.AutomationActionResult
import com.helix.extensions.mobileuse.automation.AutomationActionStatus
import com.helix.extensions.mobileuse.automation.AutomationBackendState
import com.helix.extensions.mobileuse.automation.AutomationDeviceOperation
import com.helix.extensions.mobileuse.automation.AutomationDeviceReply
import com.helix.extensions.mobileuse.automation.AutomationDeviceRequest
import com.helix.extensions.mobileuse.automation.AutomationPrivilegedBackend
import com.helix.extensions.mobileuse.automation.AutomationPrivilegedSelector
import com.helix.extensions.mobileuse.automation.hasEffect
import kotlinx.serialization.json.jsonPrimitive

/** One authenticated computer-started process. A replacement gets a new backend, never this one's frames. */
class AdbAutomationBackend(
    private val binder: IBinder,
    private val current: () -> Boolean,
) : AutomationPrivilegedBackend {
    override val deviceOperations = AutomationDeviceOperation.entries.toSet()

    override fun state() =
        if (current() &&
            binder.isBinderAlive
        ) {
            AutomationBackendState.READY
        } else {
            AutomationBackendState.LOST
        }

    @Suppress("ReturnCount") // Refuse missing transport and stale display before the single transaction.
    override fun device(
        request: AutomationDeviceRequest,
        allowed: () -> Boolean,
    ): AutomationDeviceReply {
        if (state() != AutomationBackendState.READY) {
            return AutomationDeviceReply(
                "ADB_HELPER_UNAVAILABLE",
                action =
                    if (request.operation.hasEffect()) {
                        AutomationActionResult(
                            AutomationActionStatus.ACTION_NOT_DISPATCHED,
                        )
                    } else {
                        null
                    },
            )
        }
        val owner = request.background?.owner as? ShizukuExecutionGuard
        if (owner?.bindDisplayService(binder) { checkpoint, deadline ->
                restorePrivilegedFocus(binder, checkpoint, deadline) { state() == AutomationBackendState.READY }
            } == false
        ) {
            return AutomationDeviceReply(
                "BACKGROUND_DISPLAY_STALE",
                action = AutomationActionResult(AutomationActionStatus.BACKGROUND_DISPLAY_STALE),
            )
        }
        return PrivilegedDeviceTransactions
            .execute(binder, request) {
                state() == AutomationBackendState.READY &&
                    allowed()
            }.also { reply ->
                if (reply.target?.displayId?.let { it > 0 } ==
                    true
                ) {
                    owner?.observedDisplayPackage(reply.target.packageName)
                } else {
                    owner?.displayRefused(reply.status)
                }
                if (state() != AutomationBackendState.READY) owner?.displayConnectionLost()
            }
    }

    @Suppress("TooGenericExceptionCaught") // An interrupted transaction may already have injected; never replay.
    override fun click(
        selector: AutomationPrivilegedSelector,
        allowed: (Int, Int, Int) -> Boolean,
        mayFinish: () -> Boolean,
    ): AutomationActionResult {
        if (state() !=
            AutomationBackendState.READY
        ) {
            return AutomationActionResult(AutomationActionStatus.ACTION_NOT_DISPATCHED)
        }
        return try {
            val result =
                PrivilegedUiTransactions.click(
                    binder,
                    ShizukuUiSelector(selector.packageName, selector.viewId, selector.text),
                    { x, y, rotation -> state() == AutomationBackendState.READY && allowed(x, y, rotation) },
                    { state() == AutomationBackendState.READY && mayFinish() },
                )
            AutomationActionResult(shizukuActionStatus(result["status"]?.jsonPrimitive?.content))
        } catch (_: Exception) {
            AutomationActionResult(AutomationActionStatus.ACTION_OUTCOME_UNKNOWN)
        }
    }
}
