package com.helix.tools.automation

import android.content.Context
import com.helix.core.model.SystemClock
import java.time.Duration

/** The only process-level bridge to the live Accessibility service. */
@Suppress("TooManyFunctions", "ReturnCount")
object AutomationServiceController {
    private val sessionManager = AutomationSessionManager(SystemClock())
    private var service: HelixAccessibilityService? = null
    private var grants: com.helix.core.policy.MobileUseGrantStore? = null
    private var conversationExists: (String) -> Boolean = { false }

    @Synchronized
    fun configureConversations(
        store: com.helix.core.policy.MobileUseGrantStore,
        exists: (String) -> Boolean,
    ) {
        grants = store
        conversationExists = exists
    }

    @Synchronized
    fun conversationGrant(id: String): com.helix.core.policy.MobileUseGrant? =
        if (conversationExists(id)) grants?.find(id) else null

    @Synchronized
    fun authorizeConversation(
        id: String,
        packages: Set<String>,
        wholePhone: Boolean,
    ) {
        check(conversationExists(id)) { "Conversation is not available" }
        val saved = checkNotNull(grants).authorize(id, packages, wholePhone)
        val active = sessionManager.current()
        if (active?.conversationId == id && active.scope != saved.scope) suspendRuntime()
    }

    @Synchronized
    fun revokeConversation(id: String) {
        // Persist under the same admission lock; failures also invalidate the current process's grant.
        try {
            checkNotNull(grants).revoke(id)
        } finally {
            if (sessionManager.current()?.conversationId == id) suspendRuntime()
        }
    }

    /** A retained notification can close its own grant, never whichever chat happened to run later. */
    @Synchronized
    fun stopFromNotification(
        conversationId: String?,
        scopeRef: String?,
        runtimeId: String?,
    ) {
        if (conversationId != null && scopeRef != null) {
            if (grants?.matches(conversationId, scopeRef) == true) revokeConversation(conversationId)
        } else {
            val active = sessionManager.current()
            if (active?.conversationId == null && (runtimeId == null || active?.id == runtimeId)) {
                stop(AutomationStopReason.USER_STOP)
            }
        }
    }

    private fun suspendRuntime() {
        sessionManager.stop(AutomationStopReason.SERVICE_INTERRUPTED)
        service?.invalidateSnapshotTokens()
        service?.leaveSessionForeground()
    }

    /** Exact approved scope + original Conversation. Never use the foreground chat as tool authority. */
    @Synchronized
    internal fun <T> withConversation(
        call: com.helix.tools.framework.ExecutableToolCall,
        block: () -> T,
    ): T? {
        val id = call.sessionId ?: return null
        if (call.cancel.isCancelled() ||
            !java.time.Instant
                .now()
                .isBefore(call.deadline)
        ) {
            return null
        }
        val grant = conversationGrant(id) ?: return null
        if (grant.scope.toScopeRef() != call.authorizationScopeRef) return null
        val connected = service ?: return null
        if (stopIfDeviceLocked(connected)) return null
        val previous = sessionManager.current()
        val current = sessionManager.activate(grant)
        if (previous?.id != current.id) {
            connected.invalidateSnapshotTokens()
            enterForegroundOrRollback(connected, current)
        }
        return block()
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
        if (reason == AutomationStopReason.USER_STOP) {
            sessionManager.current()?.conversationId?.let { id ->
                try {
                    checkNotNull(grants).revoke(id)
                } finally {
                    suspendRuntime()
                }
                return true
            }
        }
        val stopped = sessionManager.stop(reason)
        if (stopped) {
            service?.leaveSessionForeground()
            service?.invalidateSnapshotTokens()
        }
        return stopped
    }

    @Synchronized
    @Suppress("ReturnCount")
    fun snapshot(): AutomationSnapshotResult {
        val connectedService =
            service
                ?: return AutomationSnapshotResult(AutomationSnapshotStatus.SERVICE_NOT_CONNECTED)
        if (stopIfDeviceLocked(connectedService)) {
            return AutomationSnapshotResult(AutomationSnapshotStatus.NO_ACTIVE_SESSION)
        }
        val session =
            sessionManager.current()
                ?: return AutomationSnapshotResult(AutomationSnapshotStatus.NO_ACTIVE_SESSION)
        val result = connectedService.captureSnapshot(session)
        result.snapshot?.let { snapshot ->
            sessionManager.resumeOnVerifiedTarget(snapshot.packageName)
        }
        return result.copy(pauseReason = sessionManager.pauseReason)
    }

    @Synchronized
    fun performNodeAction(request: AutomationNodeActionRequest): AutomationActionResult {
        val connectedService =
            service
                ?: return AutomationActionResult(AutomationActionStatus.SERVICE_NOT_CONNECTED)
        if (stopIfDeviceLocked(connectedService)) return noActiveActionResult()
        val session =
            sessionManager.current()
                ?: return noActiveActionResult()
        pausedActionResult()?.let { return it }
        if (sessionManager.admitAction() != AutomationActionAdmission.ADMITTED) {
            return AutomationActionResult(AutomationActionStatus.NO_ACTIVE_SESSION)
        }
        val result = connectedService.performNodeAction(session, request)
        return completeAction(connectedService, result)
    }

    @Synchronized
    fun performGlobalAction(action: AutomationGlobalAction): AutomationActionResult {
        val connectedService =
            service
                ?: return AutomationActionResult(AutomationActionStatus.SERVICE_NOT_CONNECTED)
        if (stopIfDeviceLocked(connectedService)) return noActiveActionResult()
        val session =
            sessionManager.current()
                ?: return noActiveActionResult()
        val navigation = action in setOf(AutomationGlobalAction.BACK, AutomationGlobalAction.HOME)
        if (!navigation) pausedActionResult()?.let { return it }
        if (sessionManager.admitAction(allowPaused = navigation) != AutomationActionAdmission.ADMITTED) {
            return noActiveActionResult()
        }
        val result = connectedService.performGlobalAction(session, action)
        return completeAction(connectedService, result)
    }

    @Synchronized
    fun resumeAfterUserConfirmation(expectedPackage: String): AutomationResumeStatus {
        val connectedService = service ?: return AutomationResumeStatus.SERVICE_NOT_CONNECTED
        if (stopIfDeviceLocked(connectedService)) return AutomationResumeStatus.NO_ACTIVE_SESSION
        val session = sessionManager.current() ?: return AutomationResumeStatus.NO_ACTIVE_SESSION
        if (!sessionManager.isPaused()) return AutomationResumeStatus.NOT_PAUSED
        if (!session.scope.permitsPackage(expectedPackage)) {
            return AutomationResumeStatus.TARGET_NOT_ALLOWLISTED
        }
        val snapshot =
            connectedService.captureSnapshot(session).snapshot
                ?: return AutomationResumeStatus.SNAPSHOT_REFUSED
        if (snapshot.packageName != expectedPackage) return AutomationResumeStatus.TARGET_MISMATCH
        check(sessionManager.resumeAfterUserConfirmation()) { "paused session disappeared during resume" }
        return AutomationResumeStatus.RESUMED
    }

    @Synchronized
    fun requestResumeOnTarget(packageName: String): Boolean {
        val connectedService = service ?: return false
        if (stopIfDeviceLocked(connectedService)) return false
        val session = sessionManager.current() ?: return false
        if (!session.scope.allApplications &&
            SensitiveAutomationTargetPolicy.isDeniedPackage(packageName, session.allowSystemSettings)
        ) {
            return false
        }
        if (!sessionManager.requestResumeOnTarget(packageName)) return false
        resumeAfterUserConfirmation(packageName)
        return true
    }

    @Synchronized
    internal fun targetObserved(packageName: String) {
        val connectedService = service ?: return
        val session = sessionManager.current() ?: return
        if (packageName == sessionManager.resumeTarget) {
            resumeAfterUserConfirmation(packageName)
        }
        if (!session.scope.permitsPackage(packageName)) {
            pauseForTargetChange(connectedService)
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
        if (stopIfDeviceLocked(current)) return null
        val active = sessionManager.current() ?: return null
        val owner = active.conversationId
        if (owner != null && conversationGrant(owner)?.scope != active.scope) {
            suspendRuntime()
            return null
        }
        return current to active
    }

    @Synchronized
    internal fun <T> withDeviceLease(
        grantId: String,
        mutation: Boolean,
        block: (HelixAccessibilityService, ActiveAutomationSession) -> T,
    ): T? {
        val lease = deviceLease() ?: return null
        if (lease.second.id != grantId) return null
        if (mutation &&
            sessionManager.admitAction(allowPaused = true) != AutomationActionAdmission.ADMITTED
        ) {
            return null
        }
        return try {
            block(lease.first, lease.second)
        } finally {
            if (mutation) completeAction(lease.first, AutomationActionResult(AutomationActionStatus.SUCCEEDED))
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
