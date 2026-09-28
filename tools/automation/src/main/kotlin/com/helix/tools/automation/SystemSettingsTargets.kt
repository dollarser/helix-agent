package com.helix.tools.automation

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/** Exact platform packages shown to the user before granting a Settings session. */
internal object SystemSettingsTargets {
    val packages =
        setOf(
            "com.android.settings",
            "com.android.systemui",
            "com.android.settings.intelligence",
            "com.google.android.settings.intelligence",
        )

    fun installed(context: Context): Set<String> =
        packages.filterTo(mutableSetOf()) { name ->
            try {
                @Suppress("DEPRECATION")
                val info = context.packageManager.getApplicationInfo(name, 0)
                val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
                info.enabled && info.flags and systemFlags != 0
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }
}
