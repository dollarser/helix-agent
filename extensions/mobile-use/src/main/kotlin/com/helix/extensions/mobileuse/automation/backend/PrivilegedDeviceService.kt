package com.helix.extensions.mobileuse.automation.backend

import android.graphics.Bitmap
import android.os.IBinder
import android.os.Parcel
import android.os.SharedMemory
import com.helix.core.model.VisionLimits
import com.helix.extensions.mobileuse.automation.AutomationDeviceOperation
import com.helix.extensions.mobileuse.automation.AutomationDeviceRequest
import com.helix.extensions.mobileuse.automation.AutomationDisplayTarget
import com.helix.extensions.mobileuse.automation.hasEffect
import com.helix.extensions.mobileuse.automation.privilegedSemanticNodeMatches
import com.helix.extensions.mobileuse.automation.sameWindow
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream

internal class PrivilegedDeviceService(
    private val guard: IBinder,
    private val screen: ShizukuHierarchy,
    private val permissionControllerPackage: String?,
    private val packageManager: android.content.pm.PackageManager,
) {
    @Suppress("CyclomaticComplexMethod") // Closed protocol dispatch keeps payload ownership and publication together.
    fun execute(
        request: AutomationDeviceRequest,
        reply: Parcel,
    ) {
        check(allowed())
        val target = if (request.operation == AutomationDeviceOperation.LAUNCH) null else screen.deviceTarget()
        var image: SharedMemory? = null
        var snapshot: com.helix.extensions.mobileuse.automation.AutomationSnapshotResult? = null
        val status =
            when (request.operation) {
                AutomationDeviceOperation.LAUNCH -> {
                    PrivilegedNavigationExecution(
                        ::allowed,
                        packageManager,
                    ).launch(requireNotNull(request.launchPackage)).name
                }

                AutomationDeviceOperation.GLOBAL_ACTION -> {
                    if (target == null || request.target?.sameWindow(target) != true) {
                        "TARGET_CHANGED"
                    } else {
                        com.helix.extensions.mobileuse.automation
                            .performPlatformAutomationAction {
                                if (!allowed()) false else screen.globalAction(requireNotNull(request.globalAction))
                            }.status.name
                    }
                }

                AutomationDeviceOperation.OBSERVE -> {
                    if (target == null) "TARGET_UNAVAILABLE" else "READY"
                }

                AutomationDeviceOperation.SCREENSHOT -> {
                    if (target == null || target != request.target) {
                        "TARGET_CHANGED"
                    } else {
                        check(request.wholeDisplay && allowed())
                        image = capture(screen, target)
                        if (image == null) "TARGET_CHANGED" else "SAVED"
                    }
                }

                AutomationDeviceOperation.GESTURE -> {
                    PrivilegedGestureExecution(screen, ::allowed).execute(request)
                }

                AutomationDeviceOperation.SNAPSHOT, AutomationDeviceOperation.NODE_ACTION -> {
                    val observed = semantic(screen, request, target)
                    snapshot = observed.second
                    observed.first
                }
            }
        image.use {
            // Post-action cancellation cannot turn an uncertain input prefix into a clean refusal.
            val published =
                if (!request.operation.hasEffect() && !allowed()) {
                    "AUTHORIZATION_CHANGED"
                } else {
                    status
                }
            reply.writeNoException()
            reply.writeString(published)
            PrivilegedDeviceParcel.writeTarget(reply, if (published == status) target else null)
            reply.writeParcelable(if (published == "SAVED") it else null, 0)
            PrivilegedSemanticParcel.write(reply, if (published == status) snapshot else null)
        }
    }

    @Suppress("ReturnCount") // Refuse changed frames and missing nodes before the first side effect.
    private fun semantic(
        screen: ShizukuHierarchy,
        request: AutomationDeviceRequest,
        target: AutomationDisplayTarget?,
    ): Pair<String, com.helix.extensions.mobileuse.automation.AutomationSnapshotResult?> {
        val nodeAction = request.operation == AutomationDeviceOperation.NODE_ACTION
        if (target == null || !allowed() ||
            (if (nodeAction) request.target?.sameWindow(target) != true else target != request.target)
        ) {
            return "TARGET_CHANGED" to null
        }
        val engine =
            com.helix.extensions.mobileuse.automation
                .PrivilegedSemanticEngine(
                    target.packageName,
                    request.wholeDisplay,
                    permissionControllerPackage,
                    systemPackageInstaller(packageManager),
                )
        val snapshot = engine.capture(screen.freshRoot())
        val after = screen.deviceTarget()
        if (!allowed() || (if (nodeAction) !target.sameWindow(after) else after != target)) {
            return "TARGET_CHANGED" to null
        }
        if (request.operation == AutomationDeviceOperation.SNAPSHOT) return snapshot.status.name to snapshot
        val action = requireNotNull(request.nodeAction)
        val node =
            snapshot.snapshot
                ?.nodes
                ?.singleOrNull { it.token == action.token }
                ?: return "STALE_TOKEN" to null
        if (!privilegedSemanticNodeMatches(request, after, node)) return "TARGET_CHANGED" to null
        val permitted =
            com.helix.extensions.mobileuse.automation.privilegedSemanticAnchorPermitted(
                action.action,
                node.bounds,
                target,
            ) { x, y ->
                screen.permitsPoint(
                    ShizukuUiSelector(target.packageName, "android:id/unused", "guard"),
                    x,
                    y,
                    target.rotation,
                )
            }
        if (!permitted) return "TARGET_CHANGED" to null
        return engine.action(screen.freshRoot(), action, ::allowed).status.name to null
    }

    private fun allowed(): Boolean = ShizukuExecutionGuard.check(guard)

    @Suppress("ReturnCount") // Each read-only instability exits without publishing pixels; finally owns the bitmap.
    private fun capture(
        screen: ShizukuHierarchy,
        target: AutomationDisplayTarget,
    ): SharedMemory? {
        check(target.width.toLong() * target.height <= VisionLimits.MAX_TOTAL_PIXELS)
        val windows = screen.windows()
        val bitmap = checkNotNull(screen.screenshot()) { "SCREENSHOT_UNAVAILABLE" }
        return try {
            if (bitmap.width != target.width || bitmap.height != target.height) return null
            if (screen.deviceTarget() != target || screen.windows() != windows || !allowed()) return null
            val output = java.io.ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, BoundedCaptureOutput(output)))
            if (!allowed() || screen.deviceTarget() != target || screen.windows() != windows) return null
            readOnlyImage(output.toByteArray())
        } finally {
            bitmap.recycle()
        }
    }

    private fun readOnlyImage(bytes: ByteArray): SharedMemory {
        val memory = SharedMemory.create("helix-screen", bytes.size)
        var completed = false
        return try {
            val mapping = memory.mapReadWrite()
            try {
                mapping.put(bytes)
            } finally {
                SharedMemory.unmap(mapping)
            }
            check(memory.setProtect(android.system.OsConstants.PROT_READ))
            completed = true
            memory
        } finally {
            if (!completed) memory.close()
        }
    }
}

private class BoundedCaptureOutput(
    output: OutputStream,
) : FilterOutputStream(output) {
    private var count = 0L

    override fun write(value: Int) {
        reserve(1)
        out.write(value)
    }

    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        reserve(length)
        out.write(bytes, offset, length)
    }

    private fun reserve(length: Int) {
        count += length
        if (count > VisionLimits.MAX_INPUT_BYTES) throw IOException("SCREENSHOT_TOO_LARGE")
    }
}
