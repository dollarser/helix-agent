package com.helix.extensions.mobileuse.automation.backend

import android.content.Context

/** Platform identity queried only in shell/root; failure keeps the default sensitive-node policy. */
@Suppress("SwallowedException")
internal fun systemPermissionController(context: Context): String? =
    try {
        android.content.pm.PackageManager::class.java
            .getMethod("getPermissionControllerPackageName")
            .invoke(context.packageManager) as? String
    } catch (error: ReflectiveOperationException) {
        android.util.Log.w("HelixPrivileged", "Permission controller lookup unavailable", error)
        null
    } catch (_: SecurityException) {
        null
    }

/** Resolve the system's APK handler; an arbitrary app with matching resource names is never trusted. */
@Suppress("DEPRECATION", "SwallowedException")
internal fun systemPackageInstaller(manager: android.content.pm.PackageManager): String? =
    try {
        val intent =
            android.content
                .Intent(android.content.Intent.ACTION_INSTALL_PACKAGE)
                .setDataAndType(
                    android.net.Uri.parse("content://com.helix.validation/fixture.apk"),
                    "application/vnd.android.package-archive",
                )
        manager
            .resolveActivity(
                intent,
                android.content.pm.PackageManager.MATCH_SYSTEM_ONLY or
                    android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
            )?.activityInfo
            ?.takeIf {
                it.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0 &&
                    it.packageName != "android"
            }?.packageName
    } catch (_: SecurityException) {
        null
    }
