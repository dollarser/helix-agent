package com.helix.extensions.mobileuse.automation.backend

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import androidx.annotation.Keep

internal object ShizukuUiProtocol {
    const val DESCRIPTOR = "com.helix.app.automation.ShizukuUi"
    const val GET_UID = IBinder.FIRST_CALL_TRANSACTION + 100
    const val CLICK_MATCH = IBinder.FIRST_CALL_TRANSACTION + 101
    const val DEVICE = IBinder.FIRST_CALL_TRANSACTION + 102
    const val PREVIEW = IBinder.FIRST_CALL_TRANSACTION + 103
    const val RESTORE_FOCUS = IBinder.FIRST_CALL_TRANSACTION + 104
    const val DESTROY_AIDL = 16_777_114
    const val DESTROY_REMOTE = 16_777_115
}

@Keep
class ShizukuUiUserService
    @Keep
    constructor(
        context: Context,
    ) : Binder() {
        private val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        private val packageManager = context.packageManager
        private val displayManager = context.getSystemService(android.hardware.display.DisplayManager::class.java)
        private val permissionControllerPackage = systemPermissionController(context)
        private val ownerUid = context.applicationInfo.uid.also { require(it >= 10_000) }
        private var hierarchy: ShizukuHierarchy? = null
        private val displays = ShellBackgroundDisplays(context)
        private val cleanup =
            java.util.concurrent.Executors
                .newSingleThreadScheduledExecutor()

        init {
            cleanup.scheduleWithFixedDelay(
                { synchronized(this) { displays.sweep() } },
                1,
                1,
                java.util.concurrent.TimeUnit.SECONDS,
            )
        }

        // Release the partially connected platform object, then rethrow unchanged.
        @Suppress("TooGenericExceptionCaught")
        private fun screen(guard: IBinder): ShizukuHierarchy {
            check(ShizukuExecutionGuard.check(guard)) { "GRANT_LOST" }
            return hierarchy ?: ShizukuHierarchy().also {
                try {
                    it.connect()
                } catch (failure: Exception) {
                    releaseFailedConnection(failure, it::close)
                }
                hierarchy = it
            }
        }

        @Synchronized
        fun close() {
            cleanup.shutdownNow()
            displays.close()
            hierarchy?.close()
            hierarchy = null
        }

        init {
            attachInterface(null, ShizukuUiProtocol.DESCRIPTOR)
        }

        private fun executeDevice(
            data: Parcel,
            reply: Parcel,
        ) {
            val request = PrivilegedDeviceParcel.readRequest(data)
            val guard = requireNotNull(data.readStrongBinder())
            require(data.dataAvail() == 0) { "UNEXPECTED_ARGUMENTS" }
            synchronized(this) {
                val screen =
                    try {
                        screen(guard)
                    } catch (_: PrivilegedUiServiceUnavailable) {
                        writeDeviceRefusal(reply, "PRIVILEGED_SERVICE_UNAVAILABLE")
                        return
                    }
                val display =
                    try {
                        displays.select(request)
                    } catch (failure: IllegalStateException) {
                        writeDisplayRefusal(reply, failure.message)
                        return
                    }
                screen.route(display)
                try {
                    PrivilegedDeviceService(
                        guard,
                        screen,
                        permissionControllerPackage,
                        packageManager,
                    ).execute(request, reply)
                } finally {
                    displays.checkpoint(request)
                }
            }
        }

        private fun restoreFocus(
            data: Parcel,
            reply: Parcel,
        ) {
            require(getCallingUid() == ownerUid) { "UNTRUSTED_CALLER" }
            data.enforceInterface(ShizukuUiProtocol.DESCRIPTOR)
            val checkpoint = FocusRecoveryCheckpoint.read(data)
            val deadline = data.readLong()
            val guard = requireNotNull(data.readStrongBinder())
            require(data.dataAvail() == 0)
            val now = android.os.SystemClock.elapsedRealtime()
            val refusal =
                focusCleanupRefusal(
                    deadline - now,
                    ShizukuExecutionGuard.check(guard),
                    keyguard.isKeyguardLocked,
                    displayManager.getDisplay(checkpoint.display) != null,
                )
            val result =
                refusal ?: synchronized(this) {
                    ShellTaskRetention.restore(checkpoint) {
                        !keyguard.isKeyguardLocked && displayManager.getDisplay(checkpoint.display) == null &&
                            ShizukuExecutionGuard.check(guard)
                    }
                }
            reply.writeNoException()
            reply.writeString(result)
        }

        private fun identity(
            data: Parcel,
            reply: Parcel?,
        ) {
            require(getCallingUid() == ownerUid) { "UNTRUSTED_CALLER" }
            data.enforceInterface(ShizukuUiProtocol.DESCRIPTOR)
            require(data.dataAvail() == 0) { "UNEXPECTED_ARGUMENTS" }
            reply?.writeNoException()
            reply?.writeInt(Process.myUid())
        }

        private fun writeDisplayRefusal(
            reply: Parcel,
            detail: String?,
        ) {
            val status = if (detail == "BACKGROUND_DISPLAY_STALE") detail else "BACKGROUND_DISPLAY_UNAVAILABLE"
            writeDeviceRefusal(reply, status)
        }

        private fun writeDeviceRefusal(
            reply: Parcel,
            status: String,
        ) {
            reply.writeNoException()
            reply.writeString(status)
            PrivilegedDeviceParcel.writeTarget(reply, null)
            reply.writeParcelable(null, 0)
            PrivilegedSemanticParcel.write(reply, null)
        }

        private fun executePreview(
            data: Parcel,
            reply: Parcel,
        ): Boolean {
            require(getCallingUid() == ownerUid) { "UNTRUSTED_CALLER" }
            data.enforceInterface(ShizukuUiProtocol.DESCRIPTOR)
            val key = requireNotNull(data.readString()).also { require(it.length == 64) }
            val owner = requireNotNull(data.readStrongBinder())
            val width = data.readInt()
            require(width in BACKGROUND_PREVIEW_WIDTHS)
            val wholeDisplay = data.readInt().also { require(it in 0..1) } == 1
            require(data.dataAvail() == 0)
            val bytes = synchronized(this) { hierarchy?.let { displays.preview(key, owner, it, width, wholeDisplay) } }
            reply.writeNoException()
            reply.writeByteArray(bytes)
            return true
        }

        override fun onTransact(
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int,
        ): Boolean =
            when (code) {
                INTERFACE_TRANSACTION -> {
                    reply?.writeString(ShizukuUiProtocol.DESCRIPTOR)
                    true
                }

                ShizukuUiProtocol.GET_UID -> {
                    identity(data, reply)
                    true
                }

                ShizukuUiProtocol.CLICK_MATCH -> {
                    require(getCallingUid() == ownerUid) { "UNTRUSTED_CALLER" }
                    data.enforceInterface(ShizukuUiProtocol.DESCRIPTOR)
                    val selector =
                        ShizukuUiSelector(
                            requireNotNull(data.readString()),
                            requireNotNull(data.readString()),
                            requireNotNull(data.readString()),
                        )
                    val guard = requireNotNull(data.readStrongBinder())
                    require(data.dataAvail() == 0) { "UNEXPECTED_ARGUMENTS" }
                    val result = clickMatch(guard, selector)
                    reply?.writeNoException()
                    reply?.writeString(result)
                    true
                }

                ShizukuUiProtocol.DEVICE -> {
                    require(getCallingUid() == ownerUid) { "UNTRUSTED_CALLER" }
                    data.enforceInterface(ShizukuUiProtocol.DESCRIPTOR)
                    executeDevice(data, requireNotNull(reply))
                    true
                }

                ShizukuUiProtocol.PREVIEW -> {
                    executePreview(data, requireNotNull(reply))
                }

                ShizukuUiProtocol.RESTORE_FOCUS -> {
                    restoreFocus(data, requireNotNull(reply))
                    true
                }

                ShizukuUiProtocol.DESTROY_AIDL,
                ShizukuUiProtocol.DESTROY_REMOTE,
                -> {
                    require(getCallingUid() in setOf(ownerUid, 0, 2000)) { "UNTRUSTED_CALLER" }
                    reply?.writeNoException()
                    Thread {
                        try {
                            close()
                        } finally {
                            System.exit(0)
                        }
                    }.start()
                    true
                }

                else -> {
                    super.onTransact(code, data, reply, flags)
                }
            }

        private fun clickMatch(
            guard: IBinder,
            selector: ShizukuUiSelector,
        ): String =
            synchronized(this) {
                val connected =
                    try {
                        screen(guard)
                    } catch (_: PrivilegedUiServiceUnavailable) {
                        return@synchronized "{\"status\":\"PRIVILEGED_SERVICE_UNAVAILABLE\"}"
                    }
                ShizukuUiCommands(guard, connected.also { it.route(null) }).clickMatch(selector)
            }
    }
