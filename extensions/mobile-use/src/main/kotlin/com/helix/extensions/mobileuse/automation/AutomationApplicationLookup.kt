package com.helix.extensions.mobileuse.automation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Current-user package facts, independent of launcher visibility and foreground UI. */
@Suppress("DEPRECATION", "SwallowedException")
internal fun lookupApplication(
    context: Context,
    packageName: String,
): AutomationAppListing {
    if (!AndroidPackageName.isValid(packageName)) return AutomationAppListing("INVALID_ARGUMENT")
    val fullVisibility =
        Build.VERSION.SDK_INT < 30 ||
            context.checkSelfPermission(Manifest.permission.QUERY_ALL_PACKAGES) == PackageManager.PERMISSION_GRANTED
    val status =
        try {
            context.packageManager.getPackageInfo(packageName, 0)
            "INSTALLED"
        } catch (_: PackageManager.NameNotFoundException) {
            missingApplicationStatus(fullVisibility)
        } catch (_: SecurityException) {
            "UNKNOWN"
        }
    return AutomationAppListing(status, queriedPackage = packageName)
}

internal fun missingApplicationStatus(fullVisibility: Boolean): String =
    if (fullVisibility) "NOT_INSTALLED" else "UNKNOWN"
