package com.helix.tools.automation

import android.content.Context
import com.helix.core.model.SystemClock
import java.time.Duration

/** The only process-level bridge to the live Accessibility service. */
@Suppress("TooManyFunctions", "ReturnCount")
object AutomationServiceController {
    private val sessionManager = AutomationSessionManager(SystemClock())
    private var service: HelixAccessibilityService? = null

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
    ): AutomationSessionStartResult {
        val connectedService = service
        return if (connectedService == null) {
            AutomationSessionStartResult(AutomationSessionStartStatus.SERVICE_NOT_CONNECTED)
        } else {
            val allowlist = SharedPreferencesAutomationAllowlistStore(context).packages()
            val result = sessionManager.start(requestedPackages, allowlist, ttl, maxActions)
            result.session?.let { session ->
                enterForegroundOrRollback(connectedService, session)
            }
            result
        }
    }

    @Synchronized
    fun stop(reason: AutomationStopReason = AutomationStopReason.USER_STOP): Boolean {
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
        return connectedService.captureSnapshot(session)
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
        pausedActionResult()?.let { return it }
        if (sessionManager.admitAction() != AutomationActionAdmission.ADMITTED) {
            return AutomationActionResult(AutomationActionStatus.NO_ACTIVE_SESSION)
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
        if (expectedPackage !in session.scope.allowedPackages) {
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
    internal fun targetObserved(packageName: String) {
        val connectedService = service ?: return
        val session = sessionManager.current() ?: return
        if (packageName !in session.scope.allowedPackages) {
            pauseForTargetChange(connectedService)
        }
    }

    /** User-triggered capability revocation; Android removes this service from the enabled list. */
    @Synchronized
    fun disableSystemService(): Boolean {
        val connectedService = service ?: return false
        stop(AutomationStopReason.USER_STOP)
        connectedService.disableSelf()
        return true
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
            AutomationPauseReason.CHECKPOINT -> {
                AutomationActionResult(AutomationActionStatus.CHECKPOINT_REQUIRED)
            }

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

            AutomationActionCompletion.CHECKPOINT_REQUIRED,
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
