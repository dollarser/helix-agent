package com.helix.extensions.mobileuse.automation

import com.helix.extensions.mobileuse.config.MobileUseGrant
import com.helix.tools.framework.ExecutableToolCall

/** Select once before dispatch. Never replay navigation after a transport or execution failure. */
internal class AutomationPrivilegedNavigation(
    private val grantFor: (ExecutableToolCall) -> MobileUseGrant?,
    private val invalidate: () -> Unit = {},
) {
    @Suppress("LongMethod", "ReturnCount") // Keep admission and physical capacity owned until execution exits.
    fun execute(
        call: ExecutableToolCall,
        backend: AutomationPrivilegedBackend,
        launchPackage: String? = null,
        globalAction: AutomationGlobalAction? = null,
    ): AutomationActionResult =
        AutomationServiceController.withPhysicalOperation {
            require((launchPackage != null) != (globalAction != null))
            val grant =
                grantFor(call)
                    ?: return@withPhysicalOperation result(AutomationActionStatus.NO_ACTIVE_SESSION)
            val operation =
                if (launchPackage != null) {
                    AutomationDeviceOperation.LAUNCH
                } else {
                    AutomationDeviceOperation.GLOBAL_ACTION
                }
            admissionError(grant, backend, operation, launchPackage, globalAction)?.let {
                return@withPhysicalOperation result(it)
            }
            val ticket =
                AutomationServiceController.physicalInput.acquire()
                    ?: return@withPhysicalOperation result(AutomationActionStatus.ACTION_NOT_DISPATCHED)
            try {
                val target =
                    if (globalAction != null) {
                        backend
                            .device(
                                AutomationDeviceRequest(AutomationDeviceOperation.OBSERVE),
                            ) { grantFor(call) == grant }
                            .target
                            ?: return@withPhysicalOperation result(AutomationActionStatus.UNSUPPORTED_UI)
                    } else {
                        null
                    }
                if (grantFor(call) != grant) {
                    return@withPhysicalOperation result(AutomationActionStatus.ACTION_NOT_DISPATCHED)
                }
                invalidate()
                AutomationServiceController.invalidateObservations()
                backend
                    .device(
                        AutomationDeviceRequest(
                            operation,
                            target = target,
                            launchPackage = launchPackage,
                            globalAction = globalAction,
                        ),
                    ) { grantFor(call) == grant }
                    .action ?: result(AutomationActionStatus.ACTION_OUTCOME_UNKNOWN)
            } finally {
                invalidate()
                AutomationServiceController.invalidateObservations()
                AutomationServiceController.physicalInput.release(ticket)
            }
        }

    @Suppress("ReturnCount") // Report exact pre-dispatch refusal without executing anything.
    private fun admissionError(
        grant: MobileUseGrant,
        backend: AutomationPrivilegedBackend,
        operation: AutomationDeviceOperation,
        launchPackage: String?,
        globalAction: AutomationGlobalAction?,
    ): AutomationActionStatus? {
        if (launchPackage != null && !AndroidPackageName.isValid(launchPackage)) {
            return AutomationActionStatus.INVALID_ARGUMENT
        }
        val launchAllowed = launchPackage == null || grant.scope.permitsPackage(launchPackage)
        val globalAllowed =
            globalAction in setOf(null, AutomationGlobalAction.BACK, AutomationGlobalAction.HOME) ||
                (grant.scope.allApplications && grant.scope.deniedPackages.isEmpty())
        if (!launchAllowed || !globalAllowed) {
            return AutomationActionStatus.TARGET_NOT_ALLOWLISTED
        }
        if (operation !in backend.deviceOperations || backend.state() != AutomationBackendState.READY) {
            return AutomationActionStatus.ACTION_NOT_SUPPORTED
        }
        return null
    }

    private fun result(status: AutomationActionStatus) = AutomationActionResult(status)
}
