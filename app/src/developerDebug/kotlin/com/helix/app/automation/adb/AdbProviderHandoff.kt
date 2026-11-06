package com.helix.app.automation.adb

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.IBinder

/** app_process is not an AM-registered ApplicationThread; use the platform's external-provider entry. */
internal object AdbProviderHandoff {
    // Platform external-provider API is required for non-ApplicationThread shell clients; Debug prototype only.
    @android.annotation.SuppressLint("PrivateApi")
    fun attach(
        shell: Context,
        ownerUid: Int,
        authority: String,
        launch: String,
        extras: Bundle,
    ): Bundle {
        val managerType = Class.forName("android.app.IActivityManager")
        val manager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
        val token = Binder()
        val user = ownerUid / 100_000
        val holder =
            requireNotNull(
                managerType
                    .getMethod(
                        "getContentProviderExternal",
                        String::class.java,
                        Int::class.javaPrimitiveType,
                        IBinder::class.java,
                        String::class.java,
                    ).invoke(manager, authority, user, token, "helix-computer-adb"),
            )
        try {
            val provider = requireNotNull(holder.javaClass.getField("provider").get(holder))
            val source = Context::class.java.getMethod("getAttributionSource").invoke(shell)
            val call =
                Class.forName("android.content.IContentProvider").getMethod(
                    "call",
                    Class.forName("android.content.AttributionSource"),
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    Bundle::class.java,
                )
            return requireNotNull(call.invoke(provider, source, authority, "attach", launch, extras) as? Bundle)
        } finally {
            managerType
                .getMethod(
                    "removeContentProviderExternalAsUser",
                    String::class.java,
                    IBinder::class.java,
                    Int::class.javaPrimitiveType,
                ).invoke(manager, authority, token, user)
        }
    }
}
