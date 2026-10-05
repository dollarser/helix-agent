package com.helix.extensions.mobileuse.automation.backend

import com.helix.extensions.mobileuse.automation.AutomationActionResult
import com.helix.extensions.mobileuse.automation.AutomationActionStatus
import com.helix.extensions.mobileuse.automation.AutomationBackendState
import com.helix.extensions.mobileuse.automation.AutomationPrivilegedBackend
import com.helix.extensions.mobileuse.automation.AutomationPrivilegedSelector
import com.helix.tools.root.LibsuRootAccess
import com.helix.tools.root.RootGrantState
import kotlinx.serialization.json.jsonPrimitive

/** Passive use of an existing app grant. No shell creation, manager prompt, binding or retry here. */
class RootAutomationBackend(
    private val access: () -> LibsuRootAccess?,
) : AutomationPrivilegedBackend {
    override fun state(): AutomationBackendState {
        val connection = access() ?: return AutomationBackendState.UNAVAILABLE
        return when {
            connection.connectedBinder() != null -> AutomationBackendState.READY
            connection.status().grant == RootGrantState.LOST -> AutomationBackendState.LOST
            else -> AutomationBackendState.UNAVAILABLE
        }
    }

    override val deviceOperations =
        com.helix.extensions.mobileuse.automation.AutomationDeviceOperation.entries
            .toSet()

    @Suppress("ReturnCount") // Reject absent or mismatched privileged identity before dispatch.
    override fun device(
        request: com.helix.extensions.mobileuse.automation.AutomationDeviceRequest,
        allowed: () -> Boolean,
    ): com.helix.extensions.mobileuse.automation.AutomationDeviceReply {
        val connection = access()
        val binder =
            connection?.connectedBinder()
                ?: return com.helix.extensions.mobileuse.automation
                    .AutomationDeviceReply("ROOT_UNAVAILABLE")
        if (runCatching { PrivilegedUiTransactions.uid(binder) }.getOrNull() != 0) {
            return com.helix.extensions.mobileuse.automation
                .AutomationDeviceReply("ROOT_IDENTITY_UNAVAILABLE")
        }
        return PrivilegedDeviceTransactions.execute(binder, request) {
            connection.connectedBinder() === binder && allowed()
        }
    }

    @Suppress("TooGenericExceptionCaught") // A transport failure after dispatch has an unknown effect.
    override fun click(
        selector: AutomationPrivilegedSelector,
        allowed: (Int, Int, Int) -> Boolean,
        mayFinish: () -> Boolean,
    ): AutomationActionResult {
        val connection = access()
        val binder =
            connection?.connectedBinder()
                ?: return AutomationActionResult(AutomationActionStatus.ROOT_UNAVAILABLE)
        return try {
            check(PrivilegedUiTransactions.uid(binder) == 0) { "ROOT_IDENTITY_MISMATCH" }
            val result =
                PrivilegedUiTransactions.click(
                    binder,
                    ShizukuUiSelector(selector.packageName, selector.viewId, selector.text),
                    { x, y, rotation -> connection.connectedBinder() === binder && allowed(x, y, rotation) },
                    { connection.connectedBinder() === binder && mayFinish() },
                )
            AutomationActionResult(shizukuActionStatus(result["status"]?.jsonPrimitive?.content))
        } catch (error: Exception) {
            android.util.Log.w("HelixRootUi", "Backend failure: ${error.javaClass.simpleName}")
            AutomationActionResult(AutomationActionStatus.ACTION_OUTCOME_UNKNOWN)
        }
    }
}
