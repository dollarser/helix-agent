package com.helix.extensions.mobileuse.automation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.helix.extensions.mobileuse.automation.backend.ShizukuExecutionGuard
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
    private val adb: () -> AutomationPrivilegedBackend? = { null },
) {
    private val appContext = context.applicationContext
    private val displayLeases =
        java.util.concurrent
            .ConcurrentHashMap<String, com.helix.extensions.mobileuse.automation.backend.HostDisplayLease>()
    private val backends = AutomationBackendSelection(root, shizuku, adb)
    private val backgroundBindings = BackgroundBackendBindings(root, shizuku, adb)
    internal val privilegedDevice = AutomationPrivilegedDeviceHost(this)
    private val privilegedSemantic =
        AutomationPrivilegedSemanticHost(::liveDeviceGrant) {
            if (backgroundSelected()) "task_display" else preferredClickBackend().name.lowercase()
        }

    internal fun semanticSnapshot(call: com.helix.tools.framework.ExecutableToolCall): AutomationSnapshotResult {
        val backend = deviceBackend(AutomationDeviceOperation.SNAPSHOT, call)
        return if (backend == null) {
            AutomationServiceController.withPhysicalOperation { privilegedSemantic.invalidate() }
            withConversation(call) { snapshotForCall(call).copy(backend = "accessibility") }
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
        if (!request.token.startsWith("p") && backgroundSelected()) {
            AutomationActionResult(AutomationActionStatus.STALE_TOKEN)
        } else if (!request.token.startsWith("p")) {
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

    private val privilegedNavigation =
        AutomationPrivilegedNavigation(::liveDeviceGrant) {
            privilegedSemantic.invalidate()
            privilegedDevice.invalidate()
        }

    internal fun launch(
        call: com.helix.tools.framework.ExecutableToolCall,
        packageName: String,
    ): AutomationActionResult {
        val backend = deviceBackend(AutomationDeviceOperation.LAUNCH, call)
        return if (backend != null) {
            privilegedNavigation.execute(call, backend, launchPackage = packageName)
        } else {
            deviceLease(call)?.let { (service, session) -> service.deviceAccess.launch(session, packageName, call) }
                ?: AutomationActionResult(AutomationActionStatus.NO_ACTIVE_SESSION)
        }
    }

    internal fun globalAction(
        call: com.helix.tools.framework.ExecutableToolCall,
        action: AutomationGlobalAction,
    ): AutomationActionResult {
        val backend = deviceBackend(AutomationDeviceOperation.GLOBAL_ACTION, call)
        return if (backend != null) {
            privilegedNavigation.execute(call, backend, globalAction = action)
        } else {
            withConversation(call) { performGlobalAction(action) }
                ?: AutomationActionResult(AutomationActionStatus.NO_ACTIVE_SESSION)
        }
    }

    private val allowlist = SharedPreferencesAutomationAllowlistStore(appContext)

    fun shizukuState(): AutomationBackendState = shizuku?.state() ?: AutomationBackendState.UNAVAILABLE

    fun rootState(): AutomationBackendState = root?.state() ?: AutomationBackendState.UNAVAILABLE

    fun backgroundSelected(): Boolean = globalConfiguration()?.backgroundDisplay == true

    var backgroundCallActive: (String, String) -> Boolean = { _, _ -> false }

    fun preferredClickBackend(): AutomationClickBackend =
        if (backgroundSelected()) {
            backgroundBindings.available()?.let(backends::kind) ?: AutomationClickBackend.SHIZUKU
        } else {
            backends.preferred(AutomationDeviceOperation.SNAPSHOT) ?: AutomationClickBackend.ACCESSIBILITY
        }

    internal fun deviceBackend(
        operation: AutomationDeviceOperation = AutomationDeviceOperation.OBSERVE,
        call: com.helix.tools.framework.ExecutableToolCall? = null,
    ): AutomationPrivilegedBackend? =
        when {
            !backgroundSelected() -> {
                backends.select(operation)
            }

            call == null -> {
                backgroundBindings.available()?.takeIf { android.os.Build.VERSION.SDK_INT >= 31 }
            }

            else -> {
                backgroundBackend(call)
            }
        }

    var displayPresentation: (AutomationDisplayPresentation) -> Unit = {}
    private val backgroundGeneration =
        java.util.concurrent.atomic
            .AtomicLong()
    private val backgroundOwners =
        java.util.concurrent.ConcurrentHashMap<
            String,
            ShizukuExecutionGuard,
        >()

    /** Local user preview, not a tool observation or a grant to send images to a model. */
    @Suppress("ReturnCount") // Fail closed at each original-owner boundary, including after the IPC read.
    fun backgroundPreview(
        session: String,
        turn: String,
        generation: Long,
        width: Int,
    ): android.graphics.Bitmap? {
        val entry =
            backgroundOwners.entries.firstOrNull {
                it.value.previewService(session, turn, generation) != null
            } ?: return null
        val service = entry.value.previewService(session, turn, generation) ?: return null
        val scope = conversationGrant(session)?.scope ?: return null
        val wholeDisplay = scope.allApplications && scope.deniedPackages.isEmpty()
        return try {
            val bytes =
                com.helix.extensions.mobileuse.automation.backend.BackgroundPreviewTransport
                    .read(service, entry.key, entry.value, width, wholeDisplay) ?: return null
            if (entry.value.previewService(session, turn, generation) !== service) return null
            com.helix.extensions.mobileuse.automation.backend
                .decodeBackgroundPreview(bytes, width)
        } catch (_: android.os.RemoteException) {
            entry.value.displayConnectionLost()
            null
        } catch (_: RuntimeException) {
            // The local viewer cannot turn a failed read into a successful observation or action.
            android.util.Log.w("HelixDisplay", "Background preview unavailable")
            null
        }
    }

    private fun backgroundBackend(call: com.helix.tools.framework.ExecutableToolCall): AutomationPrivilegedBackend {
        val session = call.sessionId
        val turn = call.turnId
        val original = session?.let(::conversationGrant)
        // Resource ownership survives individual Turns. The per-request backend guard still
        // checks the current call's authorization, cancellation and deadline before input.
        val lifetime = {
            original != null && session != null && turn != null &&
                conversationGrant(session) == original && original.backgroundDisplay &&
                !appContext.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked
        }
        val key =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest("${call.sessionId}:${call.authorizationScopeRef}".toByteArray())
                .joinToString("") { "%02x".format(it) }
        if (original == null || session == null || turn == null) {
            return AutomationBackgroundBackend(
                backgroundBindings.available(),
                AutomationBackgroundRoute(
                    key,
                    ShizukuExecutionGuard({ _, _, _ -> false }, { false }),
                    false,
                ),
            )
        }
        backgroundOwners.values.forEach { it.discardUnboundDisplayIfInactive() }
        val owner =
            backgroundOwners.computeIfAbsent(key) {
                lateinit var guard: ShizukuExecutionGuard
                val lifecycle =
                    com.helix.extensions.mobileuse.automation.backend.ShizukuDisplayLifecycle(
                        requireNotNull(session),
                        requireNotNull(turn),
                        backgroundGeneration.incrementAndGet(),
                        { event -> displayPresentation(event) },
                        { target -> conversationGrant(session) == original && original.scope.permitsPackage(target) },
                        {
                            backgroundOwners.remove(key, guard)
                            backgroundBindings.forget(key)
                        },
                    )
                guard =
                    ShizukuExecutionGuard(
                        { _, _, _ -> lifetime() },
                        lifetime,
                        lifecycle,
                        displayLease(key, lifetime),
                    )
                guard
            }
        return AutomationBackgroundBackend(
            backgroundBindings.forKey(key),
            AutomationBackgroundRoute(
                key,
                owner,
                original?.allowMoveFromMain == true,
            ),
        )
    }

    private fun displayLease(
        key: String,
        lifetime: () -> Boolean,
    ) = displayLeases.compute(key) { _, existing ->
        existing?.takeUnless { it.isClosed }
            ?: com.helix.extensions.mobileuse.automation.backend
                .HostDisplayLease(appContext, lifetime) {
                    displayLeases.computeIfPresent(key) { _, value -> value.takeUnless { it.isClosed } }
                }
    }

    fun preferredBackend(operation: AutomationDeviceOperation): AutomationClickBackend? =
        (
            if (backgroundSelected()) {
                deviceBackend(operation)?.let {
                    backends.kind(it)
                }
            } else {
                backends.preferred(operation)
            }
        )
            ?: AutomationClickBackend.ACCESSIBILITY.takeIf {
                !backgroundSelected() &&
                    serviceState() == AutomationServiceState.CONNECTED
            }

    internal fun liveDeviceGrant(
        call: com.helix.tools.framework.ExecutableToolCall,
    ): com.helix.extensions.mobileuse.config.MobileUseGrant? {
        val id = call.sessionId ?: return null
        return conversationGrant(id)?.takeIf {
            it.scope.toScopeRef() == call.authorizationScopeRef && !call.cancel.isCancelled() &&
                (!it.backgroundDisplay || call.turnId?.let { turn -> backgroundCallActive(id, turn) } == true) &&
                java.time.Instant
                    .now()
                    .isBefore(call.deadline) &&
                !appContext.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked
        }
    }

    internal fun admissionError(call: com.helix.tools.framework.ExecutableToolCall): String? {
        val grant = call.sessionId?.let(::conversationGrant)
        val scopeMatches = grant != null && grant.scope.toScopeRef() == call.authorizationScopeRef
        return com.helix.extensions.mobileuse
            .MobileUseAdmission(
                hasSession = call.sessionId != null,
                hasGrant = grant != null,
                scopeMatches = scopeMatches,
                backendReady =
                    supports(
                        com.helix.extensions.mobileuse.tools.AutomationEnablement
                            .capabilityFor(call.toolName),
                    ),
                cancelled = call.cancel.isCancelled(),
                expired =
                    !java.time.Instant
                        .now()
                        .isBefore(call.deadline),
                locked = appContext.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked,
                scopeSupplied = call.authorizationScopeRef != null,
            ).error()
    }

    fun accessibilityOverlayContext(): android.content.Context? = AutomationServiceController.overlayContext()

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
        return if (frame?.target == expected) {
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

    internal fun adbClick(
        call: com.helix.tools.framework.ExecutableToolCall,
        selector: AutomationPrivilegedSelector,
    ): AutomationActionResult =
        adb()?.let { AutomationPrivilegedExecution(this).execute(call, selector, it) }
            ?: AutomationActionResult(AutomationActionStatus.ACTION_NOT_DISPATCHED)

    fun configureConversations(
        store: com.helix.extensions.mobileuse.config.MobileUseGrantStore,
        exists: (String) -> Boolean,
        enabled: () -> Boolean = { true },
    ) = AutomationServiceController.configureConversations(store, exists, enabled)

    fun conversationGrant(id: String) = AutomationServiceController.conversationGrant(id)

    fun globalConfiguration() = AutomationServiceController.globalConfiguration()

    /** System capability facts; plugin tool names and model schemas do not belong in this layer. */
    fun supports(capability: AutomationCapability): Boolean =
        capability == AutomationCapability.APPS ||
            (!backgroundSelected() && serviceState() == AutomationServiceState.CONNECTED) ||
            (capability.operation?.let { deviceBackend(it) } != null)

    fun availableConversationGrant(
        id: String,
        capability: AutomationCapability,
    ) = conversationGrant(id).takeIf { supports(capability) }

    /** Read-only discovery uses the original call identity and checks authorization again after reading. */
    @Suppress("ReturnCount") // Fail closed before reading and before publishing scoped application data.
    internal fun apps(
        call: com.helix.tools.framework.ExecutableToolCall,
        packageName: String? = null,
    ): AutomationAppListing {
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
        if (packageName != null) {
            if (!admitted.scope.permitsPackage(packageName)) return AutomationAppListing("OUT_OF_SCOPE")
            val result = lookupApplication(appContext, packageName)
            return if (grant() == admitted) result else AutomationAppListing("NO_ACTIVE_SESSION")
        }

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
        withConversation(call) {
            AutomationServiceController.deviceLease()
        }

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
            .accessibilitySettingsIntent(ComponentName(appContext, HelixAccessibilityService::class.java))

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

    internal fun snapshotForCall(call: com.helix.tools.framework.ExecutableToolCall): AutomationSnapshotResult =
        if (hasLiveSystemGrant()) {
            AutomationServiceController.snapshot(call)
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
