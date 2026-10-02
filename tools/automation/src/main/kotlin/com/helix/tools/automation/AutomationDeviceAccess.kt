package com.helix.tools.automation

import android.content.Intent
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import com.helix.tools.framework.ExecutableToolCall

/** Physical device adapter owned by the existing AccessibilityService, not a second runtime. */
internal class AutomationDeviceAccess(
    private val service: HelixAccessibilityService,
) {
    private val frames = AutomationFrameRegistry()
    private val gestures = AutomationGestureDispatch(service)
    private val captures = AutomationScreenshotCapture(service)

    fun observe(session: ActiveAutomationSession): AutomationDeviceObservation {
        val current = target()
        val permitted = current?.let { permits(session, it) } == true
        if (permitted) AutomationServiceController.targetVerified(session.id, current.packageName)
        val actions = service.availableSystemActions()
        return AutomationDeviceObservation(
            status =
                when {
                    current == null -> "TARGET_UNAVAILABLE"
                    !permitted -> "TARGET_NOT_ALLOWLISTED"
                    else -> "READY"
                },
            frame = current?.takeIf { permitted }?.let { frames.issue(session.id, it) },
            systemActions =
                AutomationGlobalAction.entries
                    .filter {
                        it.platformId in actions && (
                            session.allowSystemSettings ||
                                it in setOf(AutomationGlobalAction.BACK, AutomationGlobalAction.HOME)
                        )
                    }.toSet(),
            allApplications = session.scope.allApplications,
            screenshotSupported =
                Build.VERSION.SDK_INT >= 34 ||
                    (
                        Build.VERSION.SDK_INT >= 30 && session.scope.allApplications &&
                            session.scope.deniedPackages.isEmpty()
                    ),
            gestureSupported = true,
        )
    }

    @Suppress("DEPRECATION")
    fun apps(session: ActiveAutomationSession): List<AutomationApp> =
        service.packageManager
            .queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                0,
            ).mapNotNull { entry ->
                val packageName = entry.activityInfo?.packageName ?: return@mapNotNull null
                if (!session.scope.permitsPackage(packageName)) return@mapNotNull null
                AutomationApp(packageName, entry.loadLabel(service.packageManager).toString().take(256))
            }.distinctBy { it.packageName }
            .sortedBy { it.label }

    @Suppress("ReturnCount") // Validate the explicit target before Android receives a launch intent.
    fun launch(
        session: ActiveAutomationSession,
        packageName: String,
        call: ExecutableToolCall,
    ): AutomationActionResult {
        if (!AndroidPackageName.isValid(packageName)) return action(AutomationActionStatus.INVALID_ARGUMENT)
        if (!session.scope.permitsPackage(packageName)) return action(AutomationActionStatus.TARGET_NOT_ALLOWLISTED)
        val intent =
            service.packageManager.getLaunchIntentForPackage(packageName)
                ?: return action(AutomationActionStatus.ACTION_NOT_SUPPORTED)
        if (call.cancel.isCancelled() ||
            !java.time.Instant
                .now()
                .isBefore(call.deadline)
        ) {
            return action(AutomationActionStatus.ACTION_NOT_DISPATCHED)
        }
        return AutomationServiceController.withDeviceLease(session.id, mutation = true) { _, current ->
            if (!current.scope.permitsPackage(packageName) || call.cancel.isCancelled() ||
                !java.time.Instant
                    .now()
                    .isBefore(call.deadline)
            ) {
                action(AutomationActionStatus.ACTION_NOT_DISPATCHED)
            } else {
                frames.invalidate()
                service.invalidateSnapshotTokens()
                performPlatformAutomationAction {
                    service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                }
            }
        } ?: action(AutomationActionStatus.NO_ACTIVE_SESSION)
    }

    @Suppress("ReturnCount") // A missing or stale frame cannot be dispatched.
    fun gesture(
        session: ActiveAutomationSession,
        token: String,
        strokes: List<AutomationStroke>,
        call: ExecutableToolCall,
    ): AutomationActionResult {
        val current = target() ?: return action(AutomationActionStatus.UNSUPPORTED_UI)
        val frame = frames.resolve(token, session.id, current) ?: return action(AutomationActionStatus.STALE_TOKEN)
        if (!permits(session, current)) return action(AutomationActionStatus.TARGET_NOT_ALLOWLISTED)
        return gestures.execute(
            frame,
            session,
            strokes,
            call,
            ::target,
            { permittedWindows(session, current, strokes) },
        ) {
            frames.invalidate()
            service.invalidateSnapshotTokens()
        }
    }

    @Suppress("ReturnCount") // Frame and target admission precede capture.
    fun screenshot(
        session: ActiveAutomationSession,
        token: String,
        call: ExecutableToolCall,
    ): AutomationScreenshot {
        val current = target() ?: return AutomationScreenshot("TARGET_UNAVAILABLE")
        val frame = frames.resolve(token, session.id, current) ?: return AutomationScreenshot("STALE_TOKEN")
        if (!permits(session, current)) return AutomationScreenshot("TARGET_NOT_ALLOWLISTED")
        return captures.capture(frame, session, call, ::target)
    }

    @Suppress("DEPRECATION", "ReturnCount", "SwallowedException")
    private fun target(): AutomationDisplayTarget? {
        val root =
            try {
                service.rootInActiveWindow
            } catch (_: RuntimeException) {
                null
            } ?: return null
        var window: android.view.accessibility.AccessibilityWindowInfo? = null
        return try {
            val packageName = root.packageName?.toString() ?: return null
            window = root.window
            val displayId =
                if (Build.VERSION.SDK_INT >= 30) {
                    window?.displayId ?: Display.DEFAULT_DISPLAY
                } else {
                    Display.DEFAULT_DISPLAY
                }
            val display = service.getSystemService(DisplayManager::class.java).getDisplay(displayId) ?: return null
            val size = Point()
            display.getRealSize(size)
            val bounds = Rect()
            if (window != null) window.getBoundsInScreen(bounds) else root.getBoundsInScreen(bounds)
            val validDisplay = size.x > 0 && size.y > 0
            if (root.windowId < 0 || bounds.isEmpty || !validDisplay) return null
            AutomationDisplayTarget(
                packageName,
                root.windowId,
                displayId,
                size.x,
                size.y,
                display.rotation,
                AutomationNodeBounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
            )
        } catch (_: RuntimeException) {
            null
        } finally {
            window?.recycle()
            root.recycle()
        }
    }

    @Suppress("DEPRECATION", "SwallowedException")
    private fun permittedWindows(
        session: ActiveAutomationSession,
        target: AutomationDisplayTarget,
        strokes: List<AutomationStroke>,
    ): Boolean {
        if (session.scope.allApplications && session.scope.deniedPackages.isEmpty()) return true
        return try {
            val windows =
                if (Build.VERSION.SDK_INT >= 30) {
                    service.windowsOnAllDisplays[target.displayId].orEmpty()
                } else {
                    service.windows
                }
            try {
                val regions =
                    windows.map { window ->
                        val bounds = Rect()
                        window.getBoundsInScreen(bounds)
                        val root = window.root
                        val packageName =
                            try {
                                root?.packageName?.toString()
                            } finally {
                                root?.recycle()
                            }
                        AutomationWindowRegion(
                            window.id,
                            window.layer,
                            packageName,
                            AutomationNodeBounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
                        )
                    }
                gestureWindowsPermitted(session.scope, target, strokes, regions)
            } finally {
                windows.forEach { it.recycle() }
            }
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun permits(
        session: ActiveAutomationSession,
        target: AutomationDisplayTarget,
    ): Boolean =
        session.scope.permitsPackage(target.packageName) &&
            (
                session.scope.allApplications ||
                    !SensitiveAutomationTargetPolicy.isDeniedPackage(target.packageName, session.allowSystemSettings)
            )

    private fun action(status: AutomationActionStatus) = AutomationActionResult(status)
}

class PermissionCenterDevicePort(
    private val center: AutomationPermissionCenter,
) : AutomationDevicePort {
    override fun observe(): AutomationDeviceObservation =
        center.deviceLease()?.let { (service, session) ->
            val observed = service.deviceAccess.observe(session)
            observed.takeIf { center.deviceLease()?.second?.id == session.id }
        } ?: AutomationDeviceObservation("NO_ACTIVE_SESSION")

    override fun apps(): AutomationAppListing =
        center.deviceLease()?.let { (service, session) ->
            val apps = service.deviceAccess.apps(session)
            AutomationAppListing("LISTED", apps.take(1_000), apps.size > 1_000)
                .takeIf { center.deviceLease()?.second?.id == session.id }
        } ?: AutomationAppListing("NO_ACTIVE_SESSION")

    override fun launch(
        packageName: String,
        call: ExecutableToolCall,
    ): AutomationActionResult =
        center.deviceLease()?.let { (service, session) -> service.deviceAccess.launch(session, packageName, call) }
            ?: AutomationActionResult(AutomationActionStatus.NO_ACTIVE_SESSION)

    override fun gesture(
        frame: String,
        strokes: List<AutomationStroke>,
        call: ExecutableToolCall,
    ): AutomationActionResult =
        center.deviceLease()?.let { (service, session) -> service.deviceAccess.gesture(session, frame, strokes, call) }
            ?: AutomationActionResult(AutomationActionStatus.NO_ACTIVE_SESSION)

    override fun screenshot(
        frame: String,
        call: ExecutableToolCall,
    ): AutomationScreenshot =
        center.deviceLease()?.let { (service, session) -> service.deviceAccess.screenshot(session, frame, call) }
            ?: AutomationScreenshot("NO_ACTIVE_SESSION")
}
