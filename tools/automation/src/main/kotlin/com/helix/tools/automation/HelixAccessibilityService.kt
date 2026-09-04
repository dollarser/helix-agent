package com.helix.tools.automation

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import java.time.Duration
import java.time.Instant

/** User-enabled service for bounded snapshots and token-bound actions. */
@Suppress("TooManyFunctions")
class HelixAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val expiryStop = Runnable { AutomationServiceController.stop(AutomationStopReason.EXPIRED) }
    private val generationTracker = AccessibilityGenerationTracker()
    private val tokenRegistry = NodeTokenRegistry()
    private val snapshotEngine = AutomationSnapshotEngine(tokenRegistry)
    private val actionExecutor = AutomationNodeActionExecutor(tokenRegistry)

    override fun onServiceConnected() {
        super.onServiceConnected()
        ensureNotificationChannel()
        AutomationServiceController.connected(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        generationTracker.contentChanged()
        observeActiveTarget()
    }

    override fun onInterrupt() {
        AutomationServiceController.stop(AutomationStopReason.SERVICE_INTERRUPTED)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AutomationServiceController.disconnected(this, AutomationStopReason.SERVICE_DISCONNECTED)
        leaveSessionForeground()
        return false
    }

    override fun onDestroy() {
        AutomationServiceController.disconnected(this, AutomationStopReason.SERVICE_DISCONNECTED)
        leaveSessionForeground()
        super.onDestroy()
    }

    internal fun enterSessionForeground(session: ActiveAutomationSession) {
        ensureNotificationChannel()
        val notification = buildNotification(session)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        handler.removeCallbacks(expiryStop)
        val delay = Duration.between(Instant.now(), session.scope.expiresAt).toMillis().coerceAtLeast(0L)
        handler.postDelayed(expiryStop, delay)
    }

    internal fun leaveSessionForeground() {
        handler.removeCallbacks(expiryStop)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    internal fun captureSnapshot(session: ActiveAutomationSession): AutomationSnapshotResult =
        snapshotEngine.capture(currentRoot(), session, generationTracker.current())

    internal fun invalidateSnapshotTokens() {
        tokenRegistry.invalidate()
    }

    internal fun performNodeAction(
        session: ActiveAutomationSession,
        request: AutomationNodeActionRequest,
    ): AutomationActionResult =
        actionExecutor.execute(
            root = currentRoot(),
            session = session,
            generation = generationTracker.current(),
            request = request,
        )

    internal fun performGlobalAction(
        session: ActiveAutomationSession,
        action: AutomationGlobalAction,
    ): AutomationActionResult {
        val admission = captureSnapshot(session)
        if (admission.status != AutomationSnapshotStatus.SUCCESS) {
            return AutomationActionResult(admission.status.toActionStatus())
        }
        val platformAction =
            when (action) {
                AutomationGlobalAction.BACK -> GLOBAL_ACTION_BACK
                AutomationGlobalAction.HOME -> GLOBAL_ACTION_HOME
            }
        return AutomationActionResult(
            if (performGlobalAction(platformAction)) {
                AutomationActionStatus.SUCCEEDED
            } else {
                AutomationActionStatus.ACTION_FAILED
            },
        )
    }

    private fun currentRoot(): SnapshotNode? =
        try {
            rootInActiveWindow?.let(::AndroidSnapshotNode)
        } catch (_: RuntimeException) {
            null
        }

    private fun observeActiveTarget() {
        val root =
            try {
                rootInActiveWindow
            } catch (_: RuntimeException) {
                null
            } ?: return
        try {
            root.packageName?.toString()?.let(AutomationServiceController::targetObserved)
        } catch (_: RuntimeException) {
            // A recycled or disappearing window is not evidence of a stable target change.
        } finally {
            @Suppress("DEPRECATION")
            root.recycle()
        }
    }

    private fun AutomationSnapshotStatus.toActionStatus(): AutomationActionStatus =
        when (this) {
            AutomationSnapshotStatus.SUCCESS -> AutomationActionStatus.SUCCEEDED
            AutomationSnapshotStatus.SERVICE_NOT_CONNECTED -> AutomationActionStatus.SERVICE_NOT_CONNECTED
            AutomationSnapshotStatus.NO_ACTIVE_SESSION -> AutomationActionStatus.NO_ACTIVE_SESSION
            AutomationSnapshotStatus.TARGET_NOT_ALLOWLISTED -> AutomationActionStatus.TARGET_NOT_ALLOWLISTED
            AutomationSnapshotStatus.TARGET_CHANGED -> AutomationActionStatus.TARGET_CHANGED
            AutomationSnapshotStatus.SENSITIVE_UI -> AutomationActionStatus.SENSITIVE_UI
            AutomationSnapshotStatus.UNSUPPORTED_UI -> AutomationActionStatus.UNSUPPORTED_UI
        }

    private fun buildNotification(session: ActiveAutomationSession): Notification {
        val stopIntent = Intent(this, AutomationStopReceiver::class.java).setAction(ACTION_STOP)
        val stopPendingIntent =
            PendingIntent.getBroadcast(
                this,
                STOP_REQUEST_CODE,
                stopIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return Notification
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(getString(R.string.automation_notification_title))
            .setContentText(
                getString(
                    R.string.automation_notification_text,
                    session.scope.allowedPackages
                        .sorted()
                        .joinToString(", "),
                ),
            ).setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                Notification.Action
                    .Builder(
                        null,
                        getString(R.string.automation_notification_stop),
                        stopPendingIntent,
                    ).build(),
            ).build()
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.automation_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        const val CHANNEL_ID = "accessibility_automation"
        const val NOTIFICATION_ID = 4900
        const val ACTION_STOP = "com.helix.tools.automation.STOP_SESSION"
        private const val STOP_REQUEST_CODE = 4901
    }
}
