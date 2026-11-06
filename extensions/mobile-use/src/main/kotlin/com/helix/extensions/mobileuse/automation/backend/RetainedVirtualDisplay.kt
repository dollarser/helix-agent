package com.helix.extensions.mobileuse.automation.backend

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.IBinder
import android.view.Surface
import com.helix.extensions.mobileuse.automation.backgroundDisplayFlags

/**
 * Shell-only platform adaptation. The callback binder lives in Helix, not this helper.
 * Runs after the shell/root UID check, outside app hidden-API enforcement.
 */
@android.annotation.SuppressLint("PrivateApi", "SoonBlockedPrivateApi")
internal class RetainedVirtualDisplay(
    private val manager: DisplayManager,
    owner: IBinder,
) {
    private val type = Class.forName(HostDisplayLease.DISPLAY_DESCRIPTOR)
    private val system =
        Class
            .forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "display") as IBinder
    private val stub = Class.forName("${HostDisplayLease.DISPLAY_DESCRIPTOR}\$Stub")
    private val service = stub.getMethod("asInterface", IBinder::class.java).invoke(null, system)
    private val callbackType = Class.forName("android.hardware.display.IVirtualDisplayCallback")
    val lease =
        ShizukuExecutionGuard.lease(
            owner,
            system,
            stub.getDeclaredField("TRANSACTION_releaseVirtualDisplay").apply { isAccessible = true }.getInt(null),
        )
    private val callback =
        Class
            .forName("android.hardware.display.IVirtualDisplayCallback\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, lease.token)

    @Suppress("TooGenericExceptionCaught") // Roll back a newly created display, preserving the original failure.
    fun open(
        surface: Surface,
        trusted: Boolean,
    ): VirtualDisplay {
        val id =
            if (lease.displayId > 0) {
                type
                    .getMethod("setVirtualDisplaySurface", callbackType, Surface::class.java)
                    .invoke(service, callback, surface)
                lease.displayId
            } else {
                create(surface, trusted)
            }
        return try {
            wrap(id, surface)
        } catch (failure: Throwable) {
            if (lease.displayId < 0) {
                try {
                    type.getMethod("releaseVirtualDisplay", callbackType).invoke(service, callback)
                } catch (cleanup: Throwable) {
                    failure.addSuppressed(cleanup)
                }
            }
            throw failure
        }
    }

    private fun wrap(
        id: Int,
        surface: Surface,
    ): VirtualDisplay {
        val display = requireNotNull(manager.getDisplay(id)) { "BACKGROUND_DISPLAY_UNAVAILABLE" }
        check(display.name == "Helix background ${lease.identity}") { "BACKGROUND_DISPLAY_STALE" }
        val globalType = Class.forName("android.hardware.display.DisplayManagerGlobal")
        val global = globalType.getMethod("getInstance").invoke(null)
        return VirtualDisplay::class.java
            .getDeclaredConstructor(
                globalType,
                android.view.Display::class.java,
                callbackType,
                Surface::class.java,
            ).apply { isAccessible = true }
            .newInstance(global, display, callback, surface)
    }

    private fun create(
        surface: Surface,
        trusted: Boolean,
    ): Int {
        val configType = Class.forName("android.hardware.display.VirtualDisplayConfig")
        val builderType = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
        val builder =
            builderType
                .getConstructor(
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                ).newInstance("Helix background ${lease.identity}", 720, 1280, 320)
        builderType.getMethod("setSurface", Surface::class.java).invoke(builder, surface)
        builderType
            .getMethod("setFlags", Int::class.javaPrimitiveType)
            .invoke(builder, backgroundDisplayFlags(Build.VERSION.SDK_INT, trusted))
        val config = builderType.getMethod("build").invoke(builder)
        return try {
            type
                .getMethod(
                    "createVirtualDisplay",
                    configType,
                    callbackType,
                    Class.forName("android.media.projection.IMediaProjection"),
                    String::class.java,
                ).invoke(service, config, callback, null, "com.android.shell") as Int
        } catch (failure: java.lang.reflect.InvocationTargetException) {
            throw failure.targetException
        }
    }
}
