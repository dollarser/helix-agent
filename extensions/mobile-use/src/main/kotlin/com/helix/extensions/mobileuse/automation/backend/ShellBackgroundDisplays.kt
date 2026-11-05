package com.helix.extensions.mobileuse.automation.backend

import android.content.Context
import com.helix.extensions.mobileuse.automation.AutomationDeviceRequest
import com.helix.extensions.mobileuse.automation.AutomationDisplayLifecycle

/** UserService monitor serializes execution and cleanup; no display is released during an input prefix. */
@android.annotation.SuppressLint("NewApi") // select gates creation at API31; empty pools on older systems.
internal class ShellBackgroundDisplays(
    private val context: Context,
) : AutoCloseable {
    private data class Owned(
        val owner: android.os.IBinder,
        val display: ShellBackgroundDisplay,
    )

    private val entries = mutableMapOf<String, Owned>()

    @Suppress("ReturnCount") // No pixels without an existing owner, matching display and current scoped target.
    fun preview(
        key: String,
        owner: android.os.IBinder,
        screen: ShizukuHierarchy,
        width: Int,
        wholeDisplay: Boolean,
    ): ByteArray? {
        val owned = entries[key] ?: return null
        if (owned.owner != owner) return null
        return readBackgroundPreview(
            read = { previewAttempt(owned, owner, screen, width, wholeDisplay) },
            pause = { android.os.SystemClock.sleep(16) },
        )
    }

    @Suppress("ReturnCount") // Refusals never retry; only an authorized transition can be sampled again.
    private fun previewAttempt(
        owned: Owned,
        owner: android.os.IBinder,
        screen: ShizukuHierarchy,
        width: Int,
        wholeDisplay: Boolean,
    ): BackgroundPreviewRead {
        if (!owned.display.valid || !ShizukuExecutionGuard.check(owner)) return BackgroundPreviewRead.Refused
        screen.route(owned.display)
        val target =
            screen.deviceTarget(includeRevision = false, allowVisualFallback = wholeDisplay)
                ?: return BackgroundPreviewRead.Transition
        if (!permittedTarget(owned, owner, target)) {
            return BackgroundPreviewRead.Refused
        }
        val bytes = owned.display.preview(width)
        if (!owned.display.valid || !ShizukuExecutionGuard.check(owner) ||
            !ShizukuExecutionGuard.permitsPreviewPackage(owner, target.packageName)
        ) {
            return BackgroundPreviewRead.Refused
        }
        val current =
            screen.deviceTarget(includeRevision = false, allowVisualFallback = wholeDisplay)
                ?: return BackgroundPreviewRead.Transition
        if (!permittedTarget(owned, owner, current)) {
            return BackgroundPreviewRead.Refused
        }
        return if (bytes != null && current == target) {
            BackgroundPreviewRead.Frame(bytes)
        } else {
            BackgroundPreviewRead.Transition
        }
    }

    private fun permittedTarget(
        owned: Owned,
        owner: android.os.IBinder,
        target: com.helix.extensions.mobileuse.automation.AutomationDisplayTarget,
    ): Boolean =
        target.displayId == owned.display.id && target.displaySession == owned.display.identity &&
            ShizukuExecutionGuard.permitsPreviewPackage(owner, target.packageName)

    fun select(request: AutomationDeviceRequest): ShellBackgroundDisplay? {
        sweep()
        val route = request.background ?: return null
        check(
            android.os.Build.VERSION.SDK_INT >= 31 && android.os.Process.myUid() in setOf(0, 2000) &&
                ShizukuExecutionGuard.check(route.owner),
        ) {
            "BACKGROUND_DISPLAY_UNAVAILABLE"
        }
        val owned =
            entries[route.key] ?: run {
                check(request.target == null) { "BACKGROUND_DISPLAY_STALE" }
                Owned(route.owner, ShellBackgroundDisplay(context)).also {
                    entries[route.key] = it
                    ShizukuExecutionGuard.displayEvent(it.owner, AutomationDisplayLifecycle.ACTIVE)
                }
            }
        check(owned.display.valid) { "BACKGROUND_DISPLAY_STALE" }
        request.target?.let {
            check(it.displayId == owned.display.id && it.displaySession == owned.display.identity) {
                "BACKGROUND_DISPLAY_STALE"
            }
        }
        return owned.display
    }

    fun sweep() {
        val expired = entries.filterValues { !ShizukuExecutionGuard.check(it.owner) }.keys
        expired.forEach { key -> entries.remove(key)?.let(::release) }
    }

    @Suppress("TooGenericExceptionCaught") // Cleanup metadata never masks execution or its original exception.
    fun checkpoint(request: AutomationDeviceRequest) {
        try {
            val route = request.background ?: return
            val owned = entries[route.key]?.takeIf { it.owner == route.owner && it.display.valid } ?: return
            ShellTaskRetention
                .checkpoint(
                    owned.display.id,
                )?.let { ShizukuExecutionGuard.focusCheckpoint(owned.owner, it) }
        } catch (failure: Exception) {
            android.util.Log.w("HelixDisplay", "Display cleanup checkpoint unavailable", failure)
        }
    }

    @Suppress("TooGenericExceptionCaught") // Report failed cleanup, then rethrow the original platform failure.
    private fun release(owned: Owned) {
        try {
            owned.display.close()
        } catch (failure: Exception) {
            ShizukuExecutionGuard.displayEvent(owned.owner, AutomationDisplayLifecycle.RELEASE_UNKNOWN)
            throw failure
        }
        ShizukuExecutionGuard.displayEvent(owned.owner, AutomationDisplayLifecycle.RELEASED)
    }

    override fun close() {
        val resources = entries.values.toList()
        entries.clear()
        resources.forEach(::release)
    }
}
