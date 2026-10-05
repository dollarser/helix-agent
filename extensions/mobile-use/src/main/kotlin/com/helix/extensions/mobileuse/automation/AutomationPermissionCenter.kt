package com.helix.extensions.mobileuse.automation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import java.time.Duration

enum class AutomationServiceState {
    DISABLED,
    ENABLED_DISCONNECTED,
    CONNECTED,
    CHECK_FAILED,
}

/**
 * Backend for the user-facing Accessibility permission center. It returns the system Settings
 * intent instead of launching it, so only an explicit UI action can open the grant screen.
 */
@Suppress("TooManyFunctions")
class AutomationPermissionCenter(
    context: Context,
    private val shizuku: AutomationPrivilegedBackend? = null,
    private val root: AutomationPrivilegedBackend? = null,
) {
    private val appContext = context.applicationContext
    private val backends = AutomationBackendSelection(root, shizuku)
    internal val privilegedDevice = AutomationPrivilegedDeviceHost(this)
    private val privilegedSemantic =
        AutomationPrivilegedSemanticHost(::liveDeviceGrant) {
            preferredClickBackend().name.lowercase()
        }

    internal fun semanticSnapshot(call: com.helix.tools.framework.ExecutableToolCall): AutomationSnapshotResult {
        val backend = deviceBackend(AutomationDeviceOperation.SNAPSHOT)
        return if (backend == null) {
            AutomationServiceController.withPhysicalOperation { privilegedSemantic.invalidate() }
            withConversation(call) { snapshot().copy(backend = "accessibility") }
                ?: AutomationSnapshotResult(AutomationSnapshotStatus.NO_ACTIVE_SESSION)
        } else {
            AutomationServiceController.withPhysicalOperation {
                val ticket = AutomationServiceController.physicalInput.acquire()
                if (ticket == null) {
                    AutomationSnapshotResult(AutomationSnapshotStatus.UNSUPPORTED_UI)
                } else {
                    try {
                        AutomationServiceController.invalidateObservations()
                        privilegedSemantic.snapshot(call, backend)
                    } finally {
                        AutomationServiceController.physicalInput.release(ticket)
                    }
                }
            }
        }
    }

    internal fun semanticAction(
        call: com.helix.tools.framework.ExecutableToolCall,
        request: AutomationNodeActionRequest,
    ): AutomationActionResult =
        if (!request.token.startsWith("p")) {
            withConversation(call) { performNodeAction(request) }
                ?: AutomationActionResult(AutomationActionStatus.NO_ACTIVE_SESSION)
        } else {
            AutomationServiceController.withPhysicalOperation {
                val ticket = AutomationServiceController.physicalInput.acquire()
                if (ticket == null) {
                    AutomationActionResult(AutomationActionStatus.ACTION_NOT_DISPATCHED)
                } else {
                    try {
                        privilegedSemantic.action(call, request)
                    } finally {
                        AutomationServiceController.physicalInput.release(ticket)
                    }
                }
            }
        }

    private val allowlist = SharedPreferencesAutomationAllowlistStore(appContext)

    fun shizukuState(): AutomationBackendState = shizuku?.state() ?: AutomationBackendState.UNAVAILABLE

    fun rootState(): AutomationBackendState = root?.state() ?: AutomationBackendState.UNAVAILABLE

    fun preferredClickBackend(): AutomationClickBackend =
        backends.preferred(AutomationDeviceOperation.SNAPSHOT) ?: AutomationClickBackend.ACCESSIBILITY

    internal fun deviceBackend(
        operation: AutomationDeviceOperation = AutomationDeviceOperation.OBSERVE,
    ): AutomationPrivilegedBackend? = backends.select(operation)

    fun preferredBackend(operation: AutomationDeviceOperation): AutomationClickBackend? =
        backends.preferred(operation)
            ?: AutomationClickBackend.ACCESSIBILITY.takeIf { serviceState() == AutomationServiceState.CONNECTED }

    internal fun liveDeviceGrant(
        call: com.helix.tools.framework.ExecutableToolCall,
    ): com.helix.extensions.mobileuse.config.MobileUseGrant? {
        val id = call.sessionId ?: return null
        return conversationGrant(id)?.takeIf {
            it.scope.toScopeRef() == call.authorizationScopeRef && !call.cancel.isCancelled() &&
                java.time.Instant
                    .now()
                    .isBefore(call.deadline) &&
                !appContext.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked
        }
    }

    internal fun windowCaptureAvailable(): Boolean =
        android.os.Build.VERSION.SDK_INT >= 34 &&
            serviceState() == AutomationServiceState.CONNECTED

    @Suppress("ReturnCount") // Refuse unsupported capture before acquiring an Accessibility lease.
    internal fun captureAuthorizedWindow(
        call: com.helix.tools.framework.ExecutableToolCall,
        expected: AutomationDisplayTarget,
    ): AutomationScreenshot {
        if (!windowCaptureAvailable()) return AutomationScreenshot("WINDOW_CAPTURE_REQUIRES_ACCESSIBILITY_API_34")
        val lease = deviceLease(call) ?: return AutomationScreenshot("WINDOW_CAPTURE_REQUIRES_ACCESSIBILITY")
        val (service, session) = lease
        val frame = service.deviceAccess.observe(session).frame
        return if (frame?.target?.copy(revision = "") ==
            expected.copy(revision = "")
        ) {
            service.deviceAccess.screenshot(session, frame.token, call)
        } else {
            AutomationScreenshot("TARGET_CHANGED")
        }
    }

    internal fun rootClick(
        call: com.helix.tools.framework.ExecutableToolCall,
        selector: AutomationPrivilegedSelector,
    ): AutomationActionResult =
        root?.let { AutomationPrivilegedExecution(this).execute(call, selector, it) }
            ?: AutomationActionResult(AutomationActionStatus.ROOT_UNAVAILABLE)

    internal fun shizukuClick(
        call: com.helix.tools.framework.ExecutableToolCall,
        selector: AutomationPrivilegedSelector,
    ): AutomationActionResult =
        shizuku?.let { AutomationPrivilegedExecution(this).execute(call, selector, it) }
            ?: AutomationActionResult(AutomationActionStatus.SHIZUKU_UNAVAILABLE)

    fun configureConversations(
        store: com.helix.extensions.mobileuse.config.MobileUseGrantStore,
        exists: (String) -> Boolean,
        enabled: () -> Boolean = { true },
    ) = AutomationServiceController.configureConversations(store, exists, enabled)

    fun conversationGrant(id: String) = AutomationServiceController.conversationGrant(id)

    fun globalConfiguration() = AutomationServiceController.globalConfiguration()

    /** System capability facts; plugin tool names and model schemas do not belong in this layer. */
    fun supports(capability: AutomationCapability): Boolean =
        capability == AutomationCapability.APPS || serviceState() == AutomationServiceState.CONNECTED ||
            (capability.operation?.let { deviceBackend(it) } != null)

    fun availableConversationGrant(
        id: String,
        capability: AutomationCapability,
    ) = conversationGrant(id).takeIf { supports(capability) }

    /** Read-only discovery uses the original call identity and checks authorization again after reading. */
    @Suppress("ReturnCount") // Fail closed before reading and before publishing scoped application data.
    internal fun apps(call: com.helix.tools.framework.ExecutableToolCall): AutomationAppListing {
        val id = call.sessionId ?: return AutomationAppListing("NO_ACTIVE_SESSION")

        fun grant() =
            conversationGrant(id)?.takeIf {
                it.scope.toScopeRef() == call.authorizationScopeRef &&
                    !call.cancel.isCancelled() &&
                    java.time.Instant
                        .now()
                        .isBefore(call.deadline)
            }
        val admitted = grant() ?: return AutomationAppListing("NO_ACTIVE_SESSION")
        val apps =
            AutomationApplicationCatalog(appContext)
                .load()
                .filter { it.enabled && it.launchable && admitted.scope.permitsPackage(it.packageName) }
                .map { AutomationApp(it.packageName, it.label.take(256)) }
        if (grant() != admitted) return AutomationAppListing("NO_ACTIVE_SESSION")
        return AutomationAppListing("LISTED", apps.take(1_000), apps.size > 1_000)
    }

    internal fun privilegedCallAllowed(
        call: com.helix.tools.framework.ExecutableToolCall,
        packageName: String,
    ): Boolean {
        val id = call.sessionId ?: return false
        return privilegedCallAdmitted(
            call,
            conversationGrant(id),
            packageName,
            appContext.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked,
        )
    }

    fun conversationRuntime(id: String) = AutomationServiceController.conversationRuntime(id)

    internal fun <T> withConversation(
        call: com.helix.tools.framework.ExecutableToolCall,
        block: () -> T,
    ): T? = if (hasLiveSystemGrant()) AutomationServiceController.withConversation(call, block) else null

    internal fun deviceLease(call: com.helix.tools.framework.ExecutableToolCall) =
        withConversation(call) { AutomationServiceController.deviceLease() }

    fun serviceState(): AutomationServiceState =
        try {
            when {
                !reconcileSystemGrant() -> AutomationServiceState.DISABLED
                AutomationServiceController.isConnected() -> AutomationServiceState.CONNECTED
                else -> AutomationServiceState.ENABLED_DISCONNECTED
            }
        } catch (_: SecurityException) {
            AutomationServiceController.systemGrantRevoked()
            AutomationServiceState.CHECK_FAILED
        }

    fun accessibilitySettingsIntent(): Intent =
        com.helix.tools.deviceaccess.DeviceAccess
            .accessibilitySettingsIntent()

    fun allowlistedPackages(): Set<String> = allowlist.packages()

    fun replaceAllowlist(packages: Set<String>): Set<String> =
        AutomationServiceController.replaceAllowlist(appContext, packages)

    fun systemSettingsPackages(): Set<String> = SystemSettingsTargets.installed(appContext)

    fun requestResumeOnTarget(packageName: String): Boolean =
        hasLiveSystemGrant() && AutomationServiceController.requestResumeOnTarget(packageName)

    fun startSession(
        targetPackages: Set<String>,
        ttl: Duration = AutomationSessionManager.DEFAULT_TTL,
        maxActions: Int = AutomationSessionManager.DEFAULT_MAX_ACTIONS,
        allowSystemSettings: Boolean = false,
        allApplications: Boolean = false,
    ): AutomationSessionStartResult =
        if (hasLiveSystemGrant()) {
            AutomationServiceController.startUserSession(
                appContext,
                targetPackages,
                ttl,
                maxActions,
                allowSystemSettings,
                allApplications,
            )
        } else {
            AutomationSessionStartResult(AutomationSessionStartStatus.SERVICE_NOT_CONNECTED)
        }

    internal fun deviceLease(): Pair<HelixAccessibilityService, ActiveAutomationSession>? =
        if (hasLiveSystemGrant()) AutomationServiceController.deviceLease() else null

    fun stopSession(): Boolean = AutomationServiceController.stop()

    fun disableService(): Boolean =
        if (hasLiveSystemGrant()) {
            AutomationServiceController.disableSystemService()
        } else {
            false
        }

    fun activeSession(): ActiveAutomationSession? =
        if (hasLiveSystemGrant()) AutomationServiceController.activeSession() else null

    /** Module API used by the acceptance fixture; this is not registered as an Agent Tool. */
    fun snapshot(): AutomationSnapshotResult =
        if (hasLiveSystemGrant()) {
            AutomationServiceController.snapshot()
        } else {
            AutomationSnapshotResult(AutomationSnapshotStatus.SERVICE_NOT_CONNECTED)
        }

    fun performNodeAction(request: AutomationNodeActionRequest): AutomationActionResult =
        if (hasLiveSystemGrant()) {
            AutomationServiceController.performNodeAction(request)
        } else {
            AutomationActionResult(AutomationActionStatus.SERVICE_NOT_CONNECTED)
        }

    fun performGlobalAction(action: AutomationGlobalAction): AutomationActionResult =
        if (hasLiveSystemGrant()) {
            AutomationServiceController.performGlobalAction(action)
        } else {
            AutomationActionResult(AutomationActionStatus.SERVICE_NOT_CONNECTED)
        }

    fun pauseReason(): AutomationPauseReason? =
        if (hasLiveSystemGrant()) AutomationServiceController.pauseReason() else null

    fun lastStopReason(): AutomationStopReason? = AutomationServiceController.lastStopReason()

    /** Only an explicit user confirmation on the currently visible allowlisted package resumes. */
    fun resumeAfterUserConfirmation(expectedPackage: String): AutomationResumeStatus =
        if (hasLiveSystemGrant()) {
            AutomationServiceController.resumeAfterUserConfirmation(expectedPackage)
        } else {
            AutomationResumeStatus.SERVICE_NOT_CONNECTED
        }

    private fun hasLiveSystemGrant(): Boolean =
        try {
            reconcileSystemGrant()
        } catch (_: SecurityException) {
            AutomationServiceController.systemGrantRevoked()
            false
        }

    private fun reconcileSystemGrant(): Boolean {
        val enabled = isSystemEnabled()
        if (!enabled) AutomationServiceController.systemGrantRevoked()
        return enabled
    }

    private fun isSystemEnabled(): Boolean =
        com.helix.tools.deviceaccess.DeviceAccess.accessibilityEnabled(
            appContext,
            ComponentName(appContext, HelixAccessibilityService::class.java),
        )
}
