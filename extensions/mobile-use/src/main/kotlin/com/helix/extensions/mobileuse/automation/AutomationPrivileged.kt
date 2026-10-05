package com.helix.extensions.mobileuse.automation

import com.helix.tools.framework.ExecutableToolCall
import java.time.Instant

enum class AutomationBackendState { UNAVAILABLE, PERMISSION_REQUIRED, READY, LOST }

data class AutomationPrivilegedSelector(
    val packageName: String,
    val viewId: String,
    val text: String,
) {
    init {
        require(packageName.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+")))
        require(viewId.matches(Regex("[a-zA-Z][a-zA-Z0-9_.]*:id/[a-zA-Z0-9_]+")))
        require(text.isNotBlank() && text.length <= 256)
    }
}

/** The adapter must recheck the live host guard before injecting, and never retry a dispatch. */
interface AutomationPrivilegedBackend {
    fun state(): AutomationBackendState

    /** Declared operations, independent of live authorization/connection. */
    val deviceOperations: Set<AutomationDeviceOperation> get() = emptySet()

    val supportsDevice: Boolean get() = AutomationDeviceOperation.OBSERVE in deviceOperations

    fun device(
        request: AutomationDeviceRequest,
        allowed: () -> Boolean,
    ): AutomationDeviceReply = AutomationDeviceReply("BACKEND_UNSUPPORTED")

    fun click(
        selector: AutomationPrivilegedSelector,
        allowed: (Int, Int, Int) -> Boolean,
        mayFinish: () -> Boolean,
    ): AutomationActionResult
}

/** Original call admission and the same physical lease used by Accessibility and gestures. */
internal class AutomationPrivilegedExecution(
    private val center: AutomationPermissionCenter,
) {
    fun execute(
        call: ExecutableToolCall,
        selector: AutomationPrivilegedSelector,
        backend: AutomationPrivilegedBackend,
    ): AutomationActionResult =
        if (center.serviceState() != AutomationServiceState.CONNECTED) {
            executeIndependent(call, selector, backend)
        } else {
            executeConnected(call, selector, backend)
        }

    private fun executeConnected(
        call: ExecutableToolCall,
        selector: AutomationPrivilegedSelector,
        backend: AutomationPrivilegedBackend,
    ): AutomationActionResult =
        center.withConversation(call) {
            AutomationServiceController.withPhysicalOperation {
                val lease = center.deviceLease() ?: return@withPhysicalOperation refused()
                val (service, session) = lease
                if (!session.scope.permitsPackage(selector.packageName)) {
                    return@withPhysicalOperation AutomationActionResult(AutomationActionStatus.TARGET_NOT_ALLOWLISTED)
                }
                val ticket = service.physicalInput.acquire() ?: return@withPhysicalOperation refused()
                var hidden: AutoCloseable? = null
                try {
                    hidden = service.runtimePresentation?.hideForOperation(call)
                    if (service.runtimePresentation != null && hidden == null) return@withPhysicalOperation refused()
                    val target =
                        service.deviceAccess
                            .observe(session)
                            .frame
                            ?.target
                    if (target?.packageName != selector.packageName || target.displayId != 0) {
                        return@withPhysicalOperation AutomationActionResult(AutomationActionStatus.TARGET_CHANGED)
                    }
                    val allowed = liveGuard(call, service, session, target)
                    if (!allowed(-1, -1, -1)) return@withPhysicalOperation refused()
                    AutomationServiceController.withDeviceLease(session.id, mutation = true) { _, _ ->
                        try {
                            backend.click(selector, allowed) { liveExecution(call, service, session) }
                        } finally {
                            service.invalidateSnapshotTokens()
                        }
                    } ?: refused()
                } finally {
                    try {
                        hidden?.close()
                    } finally {
                        service.physicalInput.release(ticket)
                    }
                }
            }
        } ?: AutomationActionResult(AutomationActionStatus.NO_ACTIVE_SESSION)

    /** The privileged process validates windows; host authority never depends on a service lease. */
    private fun executeIndependent(
        call: ExecutableToolCall,
        selector: AutomationPrivilegedSelector,
        backend: AutomationPrivilegedBackend,
    ): AutomationActionResult =
        AutomationServiceController.withPhysicalOperation {
            if (!center.privilegedCallAllowed(call, selector.packageName)) return@withPhysicalOperation refused()
            val ticket = AutomationServiceController.physicalInput.acquire() ?: return@withPhysicalOperation refused()
            try {
                backend.click(selector, { _, _, _ -> center.privilegedCallAllowed(call, selector.packageName) }) {
                    center.privilegedCallAllowed(call, selector.packageName)
                }
            } finally {
                AutomationServiceController.physicalInput.release(ticket)
            }
        }

    private fun liveGuard(
        call: ExecutableToolCall,
        service: HelixAccessibilityService,
        session: ActiveAutomationSession,
        target: AutomationDisplayTarget,
    ): (Int, Int, Int) -> Boolean =
        { x, y, rotation ->
            liveExecution(call, service, session) &&
                service.deviceAccess
                    .observe(session)
                    .frame
                    ?.target == target &&
                (
                    (x == -1 && y == -1 && rotation == -1) ||
                        (
                            privilegedPointInside(target, x, y, rotation) &&
                                service.deviceAccess.permittedWindows(
                                    session,
                                    target,
                                    listOf(AutomationStroke(listOf(AutomationPoint(x.toFloat(), y.toFloat())), 0, 1)),
                                )
                        )
                )
        }

    private fun liveExecution(
        call: ExecutableToolCall,
        service: HelixAccessibilityService,
        session: ActiveAutomationSession,
    ): Boolean {
        val live = center.deviceLease()
        return !call.cancel.isCancelled() && Instant.now().isBefore(call.deadline) &&
            live?.first === service && live.second.id == session.id && live.second.scope == session.scope
    }

    private fun refused() = AutomationActionResult(AutomationActionStatus.ACTION_NOT_DISPATCHED)
}

internal fun privilegedPointInside(
    target: AutomationDisplayTarget,
    x: Int,
    y: Int,
    rotation: Int,
): Boolean =
    rotation == target.rotation && x >= target.bounds.left && x < target.bounds.right &&
        y >= target.bounds.top && y < target.bounds.bottom && x >= 0 && y >= 0 && x < target.width && y < target.height
