package com.helix.tools.automation

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.time.Instant

/** User-enabled service for bounded snapshots and token-bound actions. */
@Suppress("TooManyFunctions")
class HelixAccessibilityService : AccessibilityService() {
    @Volatile internal var runtimePresentation: AutomationRuntimePresentation? = null
        private set

    @Synchronized
    private fun ensurePresentation(): AutomationRuntimePresentation? {
        if (runtimePresentation == null) {
            runtimePresentation = AutomationRuntimePresentationFactory.create?.invoke(this)
        }
        return runtimePresentation
    }

    internal fun bindPresentation(call: com.helix.tools.framework.ExecutableToolCall): Boolean =
        ensurePresentation()?.bind(call) ?: true

    internal val physicalInput = AutomationServiceController.physicalInput

    internal val deviceAccess by lazy { AutomationDeviceAccess(this) }
    private val handler = Handler(Looper.getMainLooper())
    private var expiryStop: Runnable? = null
    private val generationTracker = AccessibilityGenerationTracker()
    private val tokenRegistry = NodeTokenRegistry()
    private val snapshotEngine = AutomationSnapshotEngine(tokenRegistry)
    private val actionExecutor = AutomationNodeActionExecutor(tokenRegistry)
    private var screenReceiverRegistered = false
    private val screenOffReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    AutomationServiceController.stop(AutomationStopReason.DEVICE_LOCKED)
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenOffReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenOffReceiver, filter)
        }
        screenReceiverRegistered = true
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ensureNotificationChannel()
        ensurePresentation()
        AutomationServiceController.connected(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || runtimePresentation?.ownsWindow(event.windowId) == true) return
        if (deviceLocked()) {
            AutomationServiceController.stop(AutomationStopReason.DEVICE_LOCKED)
            return
        }
        val activeWindow = observeActiveTarget()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            generationTracker.contentChangedInWindow(event.windowId, activeWindow)
        } else {
            generationTracker.contentChanged()
        }
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
        runtimePresentation?.close()
        AutomationServiceController.disconnected(this, AutomationStopReason.SERVICE_DISCONNECTED)
        leaveSessionForeground()
        if (screenReceiverRegistered) {
            unregisterReceiver(screenOffReceiver)
            screenReceiverRegistered = false
        }
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
        scheduleExpiry(session)
    }

    internal fun scheduleExpiry(session: ActiveAutomationSession) {
        expiryStop?.let(handler::removeCallbacks)
        expiryStop = null
        val delay = automationExpiryDelayMillis(Instant.now(), session.scope.expiresAt) ?: return
        val callback = Runnable { AutomationServiceController.recheckExpiry(this, session.id) }
        expiryStop = callback
        handler.postDelayed(callback, delay)
    }

    internal fun leaveSessionForeground() {
        runtimePresentation?.hide()
        expiryStop?.let(handler::removeCallbacks)
        expiryStop = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    internal fun captureSnapshot(session: ActiveAutomationSession): AutomationSnapshotResult =
        snapshotEngine.capture(currentRoot(), session, generationTracker.current())

    internal fun invalidateSnapshotTokens() {
        tokenRegistry.invalidate()
        deviceAccess.invalidateFrames()
    }

    internal fun currentGeneration(): Long = generationTracker.current()

    /** Lightweight target verification for resume paths; never walks the Accessibility node tree. */
    internal fun currentTargetPackage(): String? {
        val root = currentAccessibilityRoot() ?: return null
        return try {
            root.packageName?.toString()
        } catch (_: RuntimeException) {
            null
        } finally {
            root.recycleSafely()
        }
    }

    internal fun deviceLocked(): Boolean = getSystemService(KeyguardManager::class.java).isDeviceLocked

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

    @Suppress("ReturnCount") // Distinct authorization/API refusals never reach the platform action.
    internal fun performGlobalAction(
        session: ActiveAutomationSession,
        action: AutomationGlobalAction,
    ): AutomationActionResult {
        val navigation = action in setOf(AutomationGlobalAction.BACK, AutomationGlobalAction.HOME)
        if (!navigation && !session.allowSystemSettings) {
            return AutomationActionResult(AutomationActionStatus.TARGET_NOT_ALLOWLISTED)
        }
        if (Build.VERSION.SDK_INT >= 30 && systemActions.none { it.id == action.platformId }) {
            return AutomationActionResult(AutomationActionStatus.ACTION_NOT_SUPPORTED)
        }
        if (Build.VERSION.SDK_INT < 30 && action.platformId > 8) {
            return AutomationActionResult(AutomationActionStatus.ACTION_NOT_SUPPORTED)
        }
        val platformAction = action.platformId
        return try {
            performPlatformAutomationAction { performGlobalAction(platformAction) }
        } finally {
            invalidateSnapshotTokens()
        }
    }

    internal fun availableSystemActions(): Set<Int> =
        if (Build.VERSION.SDK_INT >= 30) systemActions.map { it.id }.toSet() else (1..8).toSet()

    private fun currentRoot(): SnapshotNode? = currentAccessibilityRoot()?.let(::AndroidSnapshotNode)

    /**
     * Prefer an explicit active/focused window root. Android documents that AccessibilityNodeInfo
     * snapshots can become outdated while a window mutates, and API 33+ can prefetch descendants
     * as one bounded read. Refresh once before traversal, then fall back to the active-window API.
     */
    @Suppress("DEPRECATION", "SwallowedException")
    private fun currentAccessibilityRoot(): AccessibilityNodeInfo? {
        val windows =
            try {
                windows
            } catch (_: RuntimeException) {
                emptyList()
            }
        try {
            val target =
                windows.firstOrNull(AccessibilityWindowInfo::isFocused)
                    ?: windows.firstOrNull(AccessibilityWindowInfo::isActive)
            freshWindowRoot(target)?.let { return it }
        } finally {
            windows.forEach { it.recycle() }
        }
        return freshActiveWindowRoot()
    }

    @Suppress("DEPRECATION", "SwallowedException")
    private fun freshWindowRoot(window: AccessibilityWindowInfo?): AccessibilityNodeInfo? {
        if (window == null) return null
        val root =
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    window.getRoot(rootPrefetchFlags)
                } else {
                    window.root
                }
            } catch (_: RuntimeException) {
                null
            }
        return refreshed(root)
    }

    @Suppress("DEPRECATION", "SwallowedException")
    private fun freshActiveWindowRoot(): AccessibilityNodeInfo? {
        val root =
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    getRootInActiveWindow(rootPrefetchFlags)
                } else {
                    rootInActiveWindow
                }
            } catch (_: RuntimeException) {
                null
            }
        return refreshed(root)
    }

    @Suppress("SwallowedException")
    private fun refreshed(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        return try {
            root.takeIf { it.refresh() }
        } catch (_: RuntimeException) {
            null
        }.also { refreshed ->
            if (refreshed == null) root.recycleSafely()
        }
    }

    private fun observeActiveTarget(): Int? {
        val root = currentAccessibilityRoot() ?: return null
        return try {
            root.packageName?.toString()?.let(AutomationServiceController::targetObserved)
            root.windowId
        } catch (_: RuntimeException) {
            // A recycled or disappearing window is not evidence of a stable target change.
            null
        } finally {
            root.recycleSafely()
        }
    }

    private fun buildNotification(session: ActiveAutomationSession): Notification {
        val stopIntent =
            Intent(this, AutomationStopReceiver::class.java)
                .setAction(ACTION_STOP)
                .setData(android.net.Uri.parse("helix-mobile-use:stop/${session.id}"))
                .putExtra(AutomationStopReceiver.EXTRA_CONVERSATION, session.conversationId)
                .putExtra(AutomationStopReceiver.EXTRA_SCOPE, session.scope.toScopeRef())
                .putExtra(AutomationStopReceiver.EXTRA_RUNTIME, session.id)
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
                    if (session.scope.allApplications) {
                        getString(R.string.automation_all_applications)
                    } else {
                        session.scope.allowedPackages
                            .sorted()
                            .joinToString(", ")
                    },
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

    @Suppress("DEPRECATION", "SwallowedException")
    private fun AccessibilityNodeInfo.recycleSafely() {
        try {
            recycle()
        } catch (_: RuntimeException) {
            // Best-effort cleanup only; API 33+ recycle is already a no-op.
        }
    }

    private val rootPrefetchFlags: Int =
        if (Build.VERSION.SDK_INT >= 33) {
            AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST or
                AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE
        } else {
            0
        }

    companion object {
        const val CHANNEL_ID = "accessibility_automation"
        const val NOTIFICATION_ID = 4900
        const val ACTION_STOP = "com.helix.tools.automation.STOP_SESSION"
        private const val STOP_REQUEST_CODE = 4901
    }
}
