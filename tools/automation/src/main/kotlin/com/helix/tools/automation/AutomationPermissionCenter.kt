package com.helix.tools.automation

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
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

    fun serviceState(): AutomationServiceState {
        if (AutomationServiceController.isConnected()) return AutomationServiceState.CONNECTED
        return try {
            if (isSystemEnabled()) {
                AutomationServiceState.ENABLED_DISCONNECTED
            } else {
                AutomationServiceState.DISABLED
            }
        } catch (_: SecurityException) {
            AutomationServiceState.CHECK_FAILED
        }
    }

    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun allowlistedPackages(): Set<String> = allowlist.packages()

    fun replaceAllowlist(packages: Set<String>): Set<String> =
        AutomationServiceController.replaceAllowlist(appContext, packages)

    fun startSession(
        targetPackages: Set<String>,
        ttl: Duration = AutomationSessionManager.DEFAULT_TTL,
    ): AutomationSessionStartResult = AutomationServiceController.startUserSession(appContext, targetPackages, ttl)

    fun stopSession(): Boolean = AutomationServiceController.stop()

    fun disableService(): Boolean = AutomationServiceController.disableSystemService()

    fun activeSession(): ActiveAutomationSession? = AutomationServiceController.activeSession()

    /** Module API used by the acceptance fixture; this is not registered as an Agent Tool. */
    fun snapshot(): AutomationSnapshotResult = AutomationServiceController.snapshot()

    fun performNodeAction(request: AutomationNodeActionRequest): AutomationActionResult =
        AutomationServiceController.performNodeAction(request)

    fun performGlobalAction(action: AutomationGlobalAction): AutomationActionResult =
        AutomationServiceController.performGlobalAction(action)

    fun pauseReason(): AutomationPauseReason? = AutomationServiceController.pauseReason()

    /** Only an explicit user confirmation on the currently visible allowlisted package resumes. */
    fun resumeAfterUserConfirmation(expectedPackage: String): AutomationResumeStatus =
        AutomationServiceController.resumeAfterUserConfirmation(expectedPackage)

    private fun isSystemEnabled(): Boolean {
        val manager = appContext.getSystemService(AccessibilityManager::class.java)
        return manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                val serviceInfo = info.resolveInfo.serviceInfo
                serviceInfo.packageName == appContext.packageName &&
                    serviceInfo.name == HelixAccessibilityService::class.java.name
            }
    }
}
