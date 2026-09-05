package com.helix.tools.automation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
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
) {
    private val appContext = context.applicationContext
    private val allowlist = SharedPreferencesAutomationAllowlistStore(appContext)

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
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun allowlistedPackages(): Set<String> = allowlist.packages()

    fun replaceAllowlist(packages: Set<String>): Set<String> =
        AutomationServiceController.replaceAllowlist(appContext, packages)

    fun startSession(
        targetPackages: Set<String>,
        ttl: Duration = AutomationSessionManager.DEFAULT_TTL,
        maxActions: Int = AutomationSessionManager.DEFAULT_MAX_ACTIONS,
    ): AutomationSessionStartResult =
        if (hasLiveSystemGrant()) {
            AutomationServiceController.startUserSession(appContext, targetPackages, ttl, maxActions)
        } else {
            AutomationSessionStartResult(AutomationSessionStartStatus.SERVICE_NOT_CONNECTED)
        }

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

    private fun isSystemEnabled(): Boolean {
        if (
            Settings.Secure.getInt(
                appContext.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0,
            ) != 1
        ) {
            return false
        }
        val expected = ComponentName(appContext, HelixAccessibilityService::class.java)
        return Settings.Secure
            .getString(
                appContext.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            .split(':')
            .mapNotNull(ComponentName::unflattenFromString)
            .any { it == expected }
    }
}
