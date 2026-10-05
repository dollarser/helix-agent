package com.helix.extensions.mobileuse.automation

import android.content.Context
import com.helix.core.model.SystemClock
import java.time.Duration

/** The only process-level bridge to the live Accessibility service. */
@Suppress("TooManyFunctions", "ReturnCount")
object AutomationServiceController {
    private val sessionManager = AutomationSessionManager(SystemClock())

    // Serialize model/device operations on dedicated locks, never on the controller monitor used by
    // Accessibility main-thread callbacks. The controller monitor protects only short state transitions.
    private val conversationOperationLock = Any()
    private val deviceOperationLock = Any()
    internal val physicalInput = AutomationPhysicalSlot()

    @Synchronized
    internal fun invalidateObservations() {
        service?.invalidateSnapshotTokens()
    }

    internal fun <T> withPhysicalOperation(block: () -> T): T = synchronized(deviceOperationLock, block)

    private data class OperationLease(
        val service: HelixAccessibilityService,
        val session: ActiveAutomationSession,
    )

    private data class ActionAdmission(
        val lease: OperationLease? = null,
        val refusal: AutomationActionResult? = null,
    )

    private data class ResumeAdmission(
        val lease: OperationLease? = null,
        val refusal: AutomationResumeStatus? = null,
    )

    private var service: HelixAccessibilityService? = null
    private var grants: com.helix.extensions.mobileuse.config.MobileUseGrantStore? = null
    private var pluginEnabled: () -> Boolean = { true }
    private var conversationExists: (String) -> Boolean = { false }

    @Synchronized
    fun configureConversations(
        store: com.helix.extensions.mobileuse.config.MobileUseGrantStore,
        exists: (String) -> Boolean,
        enabled: () -> Boolean = { true },
    ) {
        grants = store
        conversationExists = exists
        pluginEnabled = enabled
    }

    @Synchronized
    fun conversationGrant(id: String): com.helix.extensions.mobileuse.config.MobileUseGrant? =
        if (pluginEnabled() && conversationExists(id)) grants?.find(id) else null

    fun globalConfiguration() = grants?.globalConfiguration()

    /** Stop the original runtime/task without changing persistent plugin selection. */
    @Synchronized
    fun stopFromNotification(
        conversationId: String?,
        scopeRef: String?,
        runtimeId: String?,
    ) {
        val active = sessionManager.current() ?: return
        if (!active.matchesStop(conversationId, scopeRef, runtimeId)) return
        stop(AutomationStopReason.USER_STOP)
    }

    private fun suspendRuntime() {
        sessionManager.stop(AutomationStopReason.SERVICE_INTERRUPTED)
        service?.invalidateSnapshotTokens()
        service?.leaveSessionForeground()
    }

    /** Exact approved scope + original Conversation. Never use the foreground chat as tool authority. */
    internal fun <T> withConversation(
        call: com.helix.tools.framework.ExecutableToolCall,
        block: () -> T,
    ): T? =
        synchronized(conversationOperationLock) {
            val admitted =
                synchronized(this) {
                    val id = call.sessionId ?: return@synchronized false
                    if (call.cancel.isCancelled() ||
                        !java.time.Instant
                            .now()
                            .isBefore(call.deadline)
                    ) {
                        return@synchronized false
                    }
                    val grant = conversationGrant(id) ?: return@synchronized false
                    if (grant.scope.toScopeRef() != call.authorizationScopeRef) return@synchronized false
                    val connected = service ?: return@synchronized false
                    if (stopIfDeviceLocked(connected)) return@synchronized false
                    if (!connected.bindPresentation(call)) return@synchronized false
                    val previous = sessionManager.current()
                    val current = sessionManager.activate(grant, call.turnId)
                    if (previous?.id != current.id) {
                        connected.invalidateSnapshotTokens()
                        enterForegroundOrRollback(connected, current)
                    }
                    true
                }
            if (admitted) block() else null
        }

    /** Physical availability can change without deleting the user's Conversation configuration. */
    @Synchronized
    fun conversationRuntime(id: String): ActiveAutomationSession? =
        sessionManager.current()?.takeIf { it.conversationId == id }

    @Synchronized
    internal fun connected(instance: HelixAccessibilityService) {
        service = instance
    }

    @Synchronized
    internal fun disconnected(
        instance: HelixAccessibilityService,
        reason: AutomationStopReason,
    ) {
        if (service !== instance) return
        sessionManager.stop(reason)
        instance.invalidateSnapshotTokens()
        service = null
    }

    @Synchronized
    internal fun systemGrantRevoked() {
        sessionManager.stop(AutomationStopReason.SERVICE_DISCONNECTED)
        service?.leaveSessionForeground()
        service?.invalidateSnapshotTokens()
        service = null
    }

    @Synchronized
    fun isConnected(): Boolean = service != null

    @Synchronized
    fun activeSession(): ActiveAutomationSession? = sessionManager.current()

    @Synchronized
    fun pauseReason(): AutomationPauseReason? = sessionManager.pauseReason

    @Synchronized
    fun lastStopReason(): AutomationStopReason? = sessionManager.lastStopReason

    @Synchronized
    internal fun currentGeneration(): Long? = service?.currentGeneration()

    @Synchronized
    fun replaceAllowlist(
        context: Context,
        packages: Set<String>,
    ): Set<String> {
        val stored = SharedPreferencesAutomationAllowlistStore(context).replace(packages)
        if (sessionManager.reconcileAllowlist(stored)) {
            service?.leaveSessionForeground()
            service?.invalidateSnapshotTokens()
        }
        return stored
    }

    @Synchronized
    fun startUserSession(
        context: Context,
        requestedPackages: Set<String>,
        ttl: Duration = AutomationSessionManager.DEFAULT_TTL,
        maxActions: Int = AutomationSessionManager.DEFAULT_MAX_ACTIONS,
        allowSystemSettings: Boolean = false,
        allApplications: Boolean = false,
    ): AutomationSessionStartResult {
        val connectedService = service
        return if (connectedService == null) {
            AutomationSessionStartResult(AutomationSessionStartStatus.SERVICE_NOT_CONNECTED)
        } else {
            val allowlist = SharedPreferencesAutomationAllowlistStore(context).packages()
            val result =
                sessionManager.start(
                    requestedPackages,
                    allowlist,
                    ttl,
                    maxActions,
                    allowSystemSettings,
                    allApplications,
                )
            result.session?.let { session ->
                connectedService.invalidateSnapshotTokens()
                enterForegroundOrRollback(connectedService, session)
            }
            result
        }
    }

    @Synchronized
    fun stop(reason: AutomationStopReason = AutomationStopReason.USER_STOP): Boolean {
        if (reason == AutomationStopReason.USER_STOP) service?.runtimePresentation?.stopBoundTask()
        val stopped = sessionManager.stop(reason)
        if (stopped) {
            service?.leaveSessionForeground()
            service?.invalidateSnapshotTokens()
        }
        return stopped
    }

    @Suppress("ReturnCount")
    fun snapshot(): AutomationSnapshotResult =
        synchronized(deviceOperationLock) {
            val connectedService =
                synchronized(this) { service }
                    ?: return@synchronized AutomationSnapshotResult(AutomationSnapshotStatus.SERVICE_NOT_CONNECTED)
            if (connectedService.deviceLocked()) {
                synchronized(this) {
                    if (service === connectedService) stop(AutomationStopReason.DEVICE_LOCKED)
                }
                return@synchronized AutomationSnapshotResult(AutomationSnapshotStatus.NO_ACTIVE_SESSION)
            }
            val session =
                synchronized(this) {
                    if (service === connectedService) sessionManager.current() else null
                } ?: return@synchronized AutomationSnapshotResult(AutomationSnapshotStatus.NO_ACTIVE_SESSION)
            val result = connectedService.captureSnapshot(session)
            synchronized(this) {
                val current = sessionManager.current()
                if (service !== connectedService || current?.id != session.id) {
                    connectedService.invalidateSnapshotTokens()
                    return@synchronized AutomationSnapshotResult(AutomationSnapshotStatus.NO_ACTIVE_SESSION)
                }
                val captured = result.snapshot
                if (captured != null && captured.generation != connectedService.currentGeneration()) {
                    connectedService.invalidateSnapshotTokens()
                    return@synchronized AutomationSnapshotResult(
                        AutomationSnapshotStatus.TARGET_CHANGED,
                        pauseReason = sessionManager.pauseReason,
                        targetPackage = captured.packageName,
                    )
                }
                captured?.let { snapshot ->
                    sessionManager.resumeOnVerifiedTarget(snapshot.packageName)
                }
                result.copy(pauseReason = sessionManager.pauseReason)
            }
        }

    fun performNodeAction(request: AutomationNodeActionRequest): AutomationActionResult =
        synchronized(deviceOperationLock) {
            val admission = admitActionLease(allowPaused = false)
            admission.refusal?.let { return@synchronized it }
            val lease = checkNotNull(admission.lease)
            val result = lease.service.performNodeAction(lease.session, request)
            completeActionIfCurrent(lease, result)
        }

    fun performGlobalAction(action: AutomationGlobalAction): AutomationActionResult =
        synchronized(deviceOperationLock) {
            val navigation = action in setOf(AutomationGlobalAction.BACK, AutomationGlobalAction.HOME)
            val admission = admitActionLease(allowPaused = navigation)
            admission.refusal?.let { return@synchronized it }
            val lease = checkNotNull(admission.lease)
            val result = lease.service.performGlobalAction(lease.session, action)
            completeActionIfCurrent(lease, result)
        }

    fun resumeAfterUserConfirmation(expectedPackage: String): AutomationResumeStatus {
        val admission = admitResumeLease(expectedPackage)
        admission.refusal?.let { return it }
        val lease = checkNotNull(admission.lease)
        val currentPackage =
            lease.service.currentTargetPackage()
                ?: return AutomationResumeStatus.SNAPSHOT_REFUSED
        if (currentPackage != expectedPackage) return AutomationResumeStatus.TARGET_MISMATCH
        return synchronized(this) {
            val current = sessionManager.current()
            if (service !== lease.service || current?.id != lease.session.id) {
                AutomationResumeStatus.NO_ACTIVE_SESSION
            } else if (!sessionManager.isPaused()) {
                AutomationResumeStatus.NOT_PAUSED
            } else {
                check(sessionManager.resumeAfterUserConfirmation()) { "paused session disappeared during resume" }
                AutomationResumeStatus.RESUMED
            }
        }
    }

    fun requestResumeOnTarget(packageName: String): Boolean {
        val requested =
            synchronized(this) {
                val connectedService = service ?: return@synchronized false
                if (stopIfDeviceLocked(connectedService)) return@synchronized false
                val session = sessionManager.current() ?: return@synchronized false
                if (!session.scope.allApplications &&
                    SensitiveAutomationTargetPolicy.isDeniedPackage(packageName, session.allowSystemSettings)
                ) {
                    return@synchronized false
                }
                sessionManager.requestResumeOnTarget(packageName)
            }
        if (!requested) return false
        resumeAfterUserConfirmation(packageName)
        return true
    }

    /** Accessibility callback path: state-only and bounded; never traverses the node tree. */
    @Synchronized
    internal fun targetObserved(packageName: String) {
        val connectedService = service ?: return
        val session = sessionManager.current() ?: return
        if (!session.scope.permitsPackage(packageName)) {
            pauseForTargetChange(connectedService)
            return
        }
        if (packageName == sessionManager.resumeTarget) {
            sessionManager.resumeAfterUserConfirmation()
        }
    }

    /** User-triggered capability revocation; Android removes this service from the enabled list. */
    @Synchronized
    fun disableSystemService(): Boolean {
        val connectedService = service ?: return false
        stop(AutomationStopReason.SERVICE_DISCONNECTED)
        connectedService.disableSelf()
        return true
    }

    @Synchronized
    internal fun targetVerified(
        grantId: String,
        packageName: String,
    ) {
        val session = sessionManager.current() ?: return
        if (session.id == grantId) sessionManager.resumeOnVerifiedTarget(packageName)
    }

    /** Captures a live permission lease. Never hold this monitor while waiting for Android callbacks. */
    @Synchronized
    internal fun deviceLease(): Pair<HelixAccessibilityService, ActiveAutomationSession>? {
        val current = service ?: return null
        if (current.runtimePresentation?.executionAllowed() == false) return null
        if (stopIfDeviceLocked(current)) return null
        val active = sessionManager.current() ?: return null
        val owner = active.conversationId
        if (owner != null && conversationGrant(owner)?.scope != active.scope) {
            suspendRuntime()
            return null
        }
        return current to active
    }

    internal fun <T> withDeviceLease(
        grantId: String,
        mutation: Boolean,
        block: (HelixAccessibilityService, ActiveAutomationSession) -> T,
    ): T? {
        val lease =
            synchronized(this) {
                val current = deviceLease() ?: return@synchronized null
                if (current.second.id != grantId) return@synchronized null
                if (mutation &&
                    sessionManager.admitAction(allowPaused = true) != AutomationActionAdmission.ADMITTED
                ) {
                    return@synchronized null
                }
                OperationLease(current.first, current.second)
            } ?: return null
        return try {
            block(lease.service, lease.session)
        } finally {
            if (mutation) completeActionIfCurrent(lease, AutomationActionResult(AutomationActionStatus.SUCCEEDED))
        }
    }

    /** A queued callback from an earlier grant must never stop its replacement. */
    @Synchronized
    internal fun recheckExpiry(
        instance: HelixAccessibilityService,
        grantId: String,
    ) {
        if (service !== instance) return
        val active = sessionManager.current()
        if (active == null) {
            instance.leaveSessionForeground()
            instance.invalidateSnapshotTokens()
        } else if (active.id == grantId) {
            instance.scheduleExpiry(active)
        }
    }

    private fun admitResumeLease(expectedPackage: String): ResumeAdmission =
        synchronized(this) {
            val connectedService =
                service
                    ?: return@synchronized ResumeAdmission(refusal = AutomationResumeStatus.SERVICE_NOT_CONNECTED)
            if (stopIfDeviceLocked(connectedService)) {
                return@synchronized ResumeAdmission(refusal = AutomationResumeStatus.NO_ACTIVE_SESSION)
            }
            val session =
                sessionManager.current()
                    ?: return@synchronized ResumeAdmission(refusal = AutomationResumeStatus.NO_ACTIVE_SESSION)
            if (!sessionManager.isPaused()) {
                return@synchronized ResumeAdmission(refusal = AutomationResumeStatus.NOT_PAUSED)
            }
            if (!session.scope.permitsPackage(expectedPackage) ||
                (
                    !session.scope.allApplications &&
                        SensitiveAutomationTargetPolicy.isDeniedPackage(
                            expectedPackage,
                            session.allowSystemSettings,
                        )
                )
            ) {
                return@synchronized ResumeAdmission(refusal = AutomationResumeStatus.TARGET_NOT_ALLOWLISTED)
            }
            ResumeAdmission(lease = OperationLease(connectedService, session))
        }

    private fun admitActionLease(allowPaused: Boolean): ActionAdmission =
        synchronized(this) {
            val connectedService =
                service
                    ?: return@synchronized ActionAdmission(
                        refusal = AutomationActionResult(AutomationActionStatus.SERVICE_NOT_CONNECTED),
                    )
            if (stopIfDeviceLocked(connectedService)) {
                return@synchronized ActionAdmission(refusal = noActiveActionResult())
            }
            val session =
                sessionManager.current() ?: return@synchronized ActionAdmission(refusal = noActiveActionResult())
            if (!allowPaused) {
                pausedActionResult()?.let { return@synchronized ActionAdmission(refusal = it) }
            }
            if (sessionManager.admitAction(allowPaused = allowPaused) != AutomationActionAdmission.ADMITTED) {
                return@synchronized ActionAdmission(refusal = noActiveActionResult())
            }
            ActionAdmission(lease = OperationLease(connectedService, session))
        }

    private fun completeActionIfCurrent(
        lease: OperationLease,
        result: AutomationActionResult,
    ): AutomationActionResult =
        synchronized(this) {
            val current = sessionManager.current()
            if (service !== lease.service || current?.id != lease.session.id) {
                result
            } else {
                completeAction(lease.service, result)
            }
        }

    private fun enterForegroundOrRollback(
        connectedService: HelixAccessibilityService,
        session: ActiveAutomationSession,
    ) {
        var startFailure: RuntimeException? = null
        try {
            connectedService.enterSessionForeground(session)
        } catch (failure: SecurityException) {
            startFailure = failure
        } catch (failure: IllegalArgumentException) {
            startFailure = failure
        } catch (failure: IllegalStateException) {
            startFailure = failure
        }
        startFailure?.let { failure ->
            rollbackForegroundFailure()
            throw failure
        }
    }

    private fun rollbackForegroundFailure() {
        sessionManager.stop(AutomationStopReason.FOREGROUND_START_FAILED)
    }

    private fun pauseForTargetChange(connectedService: HelixAccessibilityService) {
        sessionManager.pause(AutomationPauseReason.TARGET_CHANGED)
        connectedService.invalidateSnapshotTokens()
    }

    private fun pausedActionResult(): AutomationActionResult? =
        when (sessionManager.pauseReason) {
            AutomationPauseReason.TARGET_CHANGED -> {
                AutomationActionResult(AutomationActionStatus.SESSION_PAUSED)
            }

            null -> {
                null
            }
        }

    private fun noActiveActionResult(): AutomationActionResult =
        AutomationActionResult(
            if (sessionManager.lastStopReason == AutomationStopReason.ACTION_BUDGET_EXHAUSTED) {
                AutomationActionStatus.ACTION_BUDGET_EXHAUSTED
            } else {
                AutomationActionStatus.NO_ACTIVE_SESSION
            },
        )

    private fun completeAction(
        connectedService: HelixAccessibilityService,
        result: AutomationActionResult,
    ): AutomationActionResult {
        when (sessionManager.completeAction()) {
            AutomationActionCompletion.BUDGET_EXHAUSTED -> {
                connectedService.leaveSessionForeground()
                connectedService.invalidateSnapshotTokens()
            }

            AutomationActionCompletion.CONTINUE,
            AutomationActionCompletion.NO_ACTIVE_SESSION,
            -> {
                Unit
            }
        }
        if (result.status == AutomationActionStatus.TARGET_CHANGED && sessionManager.current() != null) {
            pauseForTargetChange(connectedService)
        }
        return result
    }

    private fun stopIfDeviceLocked(connectedService: HelixAccessibilityService): Boolean {
        if (!connectedService.deviceLocked()) return false
        stop(AutomationStopReason.DEVICE_LOCKED)
        return true
    }
}
