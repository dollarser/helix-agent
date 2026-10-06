package com.helix.extensions.mobileuse.automation

import com.helix.tools.framework.ExecutableToolCall

/** Coordinate frames stay bound to original scope and backend; failure never triggers replay. */
internal interface AutomationDeviceAuthority {
    fun liveDeviceGrant(call: ExecutableToolCall): com.helix.extensions.mobileuse.config.MobileUseGrant?

    fun windowCaptureAvailable(): Boolean

    fun captureAuthorizedWindow(
        call: ExecutableToolCall,
        expected: AutomationDisplayTarget,
    ): AutomationScreenshot

    fun rootState(): AutomationBackendState

    fun shizukuState(): AutomationBackendState

    fun preferredClickBackend(): AutomationClickBackend
}

internal class AutomationPrivilegedDeviceHost(
    private val center: AutomationDeviceAuthority,
) {
    constructor(center: AutomationPermissionCenter) : this(
        object : AutomationDeviceAuthority {
            override fun liveDeviceGrant(call: ExecutableToolCall) = center.liveDeviceGrant(call)

            override fun windowCaptureAvailable() = center.windowCaptureAvailable()

            override fun captureAuthorizedWindow(
                call: ExecutableToolCall,
                expected: AutomationDisplayTarget,
            ) = center.captureAuthorizedWindow(call, expected)

            override fun rootState() = center.rootState()

            override fun shizukuState() = center.shizukuState()

            override fun preferredClickBackend() = center.preferredClickBackend()
        },
    )

    private data class BoundFrame(
        val frame: AutomationFrame,
        val sessionId: String?,
        val scopeRef: String?,
        val backend: AutomationPrivilegedBackend,
    )

    private var bound: BoundFrame? = null

    fun observe(
        call: ExecutableToolCall,
        backend: AutomationPrivilegedBackend,
    ): AutomationDeviceObservation =
        AutomationServiceController.withPhysicalOperation {
            bound = null
            val ticket =
                AutomationServiceController.physicalInput.acquire()
                    ?: return@withPhysicalOperation AutomationDeviceObservation("PHYSICAL_OPERATION_BUSY")
            try {
                AutomationServiceController.invalidateObservations()
                observeGranted(call, backend)
            } finally {
                AutomationServiceController.physicalInput.release(ticket)
            }
        }

    fun invalidate() = AutomationServiceController.withPhysicalOperation { bound = null }

    @Suppress("ReturnCount") // Refuse absent grants, missing targets and out-of-scope observations separately.
    private fun observeGranted(
        call: ExecutableToolCall,
        backend: AutomationPrivilegedBackend,
    ): AutomationDeviceObservation {
        val grant =
            center.liveDeviceGrant(call)
                ?: return AutomationDeviceObservation("NO_ACTIVE_SESSION")
        val reply =
            backend.device(AutomationDeviceRequest(AutomationDeviceOperation.OBSERVE)) {
                center.liveDeviceGrant(call) == grant
            }
        val target = reply.target ?: return AutomationDeviceObservation(reply.status)
        if (center.liveDeviceGrant(call) != grant || !grant.scope.permitsPackage(target.packageName)) {
            return AutomationDeviceObservation("TARGET_NOT_ALLOWLISTED")
        }
        val frame =
            AutomationFrame(
                java.util.UUID
                    .randomUUID()
                    .toString(),
                grant.scope.grantId,
                target,
            )
        bound = BoundFrame(frame, call.sessionId, call.authorizationScopeRef, backend)
        return AutomationDeviceObservation(
            "READY",
            frame,
            systemActions =
                if (AutomationDeviceOperation.GLOBAL_ACTION in backend.deviceOperations) {
                    AutomationGlobalAction.entries
                        .filter {
                            it.platformId <= 8 && (
                                wholeDisplay(grant.scope) ||
                                    it in setOf(AutomationGlobalAction.BACK, AutomationGlobalAction.HOME)
                            )
                        }.toSet()
                } else {
                    emptySet()
                },
            allApplications = grant.scope.allApplications,
            screenshotSupported =
                grant.shareScreens &&
                    (
                        (
                            wholeDisplay(
                                grant.scope,
                            ) && AutomationDeviceOperation.SCREENSHOT in backend.deviceOperations
                        ) ||
                            center.windowCaptureAvailable()
                    ),
            gestureSupported = AutomationDeviceOperation.GESTURE in backend.deviceOperations,
            rootState = center.rootState(),
            shizukuState = center.shizukuState(),
            clickMatchBackend = center.preferredClickBackend(),
        )
    }

    fun owns(token: String): Boolean =
        AutomationServiceController.withPhysicalOperation { bound?.frame?.token == token }

    @Suppress("ReturnCount") // Scope-limited capture must leave the physical monitor before acquiring a service lease.
    fun execute(
        call: ExecutableToolCall,
        token: String,
        operation: AutomationDeviceOperation,
        strokes: List<AutomationStroke> = emptyList(),
    ): AutomationDeviceReply {
        if (operation == AutomationDeviceOperation.SCREENSHOT) {
            val saved =
                AutomationServiceController.withPhysicalOperation {
                    bound?.takeIf {
                        it.frame.token == token && it.sessionId == call.sessionId &&
                            it.scopeRef == call.authorizationScopeRef
                    }
                }
            val grant = center.liveDeviceGrant(call)
            if (grant?.shareScreens == false) return AutomationDeviceReply("SCREEN_SHARING_NOT_AUTHORIZED")
            if (saved != null && grant != null && !wholeDisplay(grant.scope)) {
                val capture = center.captureAuthorizedWindow(call, saved.frame.target)
                return if (owns(token) && center.liveDeviceGrant(call) == grant) {
                    AutomationDeviceReply(capture.status, screenshot = capture)
                } else {
                    AutomationDeviceReply("FRAME_STALE")
                }
            }
        }
        return executePrivileged(call, token, operation, strokes)
    }

    private fun executePrivileged(
        call: ExecutableToolCall,
        token: String,
        operation: AutomationDeviceOperation,
        strokes: List<AutomationStroke>,
    ): AutomationDeviceReply =
        AutomationServiceController.withPhysicalOperation {
            val saved =
                bound?.takeIf {
                    it.frame.token == token && it.sessionId == call.sessionId &&
                        it.scopeRef == call.authorizationScopeRef
                } ?: return@withPhysicalOperation AutomationDeviceReply("FRAME_STALE")
            val grant =
                center.liveDeviceGrant(call)?.takeIf { it.scope.permitsPackage(saved.frame.target.packageName) }
                    ?: return@withPhysicalOperation AutomationDeviceReply("NO_ACTIVE_SESSION")
            if (operation !in saved.backend.deviceOperations) {
                return@withPhysicalOperation AutomationDeviceReply("BACKEND_UNSUPPORTED")
            }
            if (saved.backend.state() != AutomationBackendState.READY) {
                return@withPhysicalOperation AutomationDeviceReply("BACKEND_UNAVAILABLE")
            }
            if (operation == AutomationDeviceOperation.GESTURE) {
                validateAutomationGesture(strokes, saved.frame.target, wholeDisplay(grant.scope), 10, 10_000)
                bound = null
                AutomationServiceController.invalidateObservations()
            }
            val ticket =
                AutomationServiceController.physicalInput.acquire()
                    ?: return@withPhysicalOperation AutomationDeviceReply("PHYSICAL_OPERATION_BUSY")
            try {
                val reply =
                    saved.backend.device(
                        AutomationDeviceRequest(operation, saved.frame.target, strokes, wholeDisplay(grant.scope)),
                    ) { center.liveDeviceGrant(call) == grant }
                if (operation == AutomationDeviceOperation.SCREENSHOT && center.liveDeviceGrant(call) != grant) {
                    AutomationDeviceReply("AUTHORIZATION_CHANGED")
                } else {
                    val capture = reply.screenshot?.copy(acquisitionScopeRef = call.authorizationScopeRef)
                    reply.copy(screenshot = capture)
                }
            } finally {
                if (operation == AutomationDeviceOperation.GESTURE) {
                    AutomationServiceController.invalidateObservations()
                }
                AutomationServiceController.physicalInput.release(ticket)
            }
        }

    private fun wholeDisplay(scope: com.helix.core.policy.AutomationSessionScope) =
        scope.allApplications && scope.deniedPackages.isEmpty()
}
