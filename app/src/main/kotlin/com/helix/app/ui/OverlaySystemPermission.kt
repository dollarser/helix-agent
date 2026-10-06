package com.helix.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import com.helix.app.R

/** Host-owned special permission; neither a plugin nor a tool call opens the settings screen. */
@Composable
@Suppress("FunctionName")
internal fun OverlaySystemPermission(
    revision: Int,
    open: (Intent) -> Unit,
) {
    val context = LocalContext.current
    val declared =
        remember(context) {
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
                .orEmpty()
                .contains(Manifest.permission.SYSTEM_ALERT_WINDOW)
        }
    if (!declared) return
    val granted = remember(revision) { Settings.canDrawOverlays(context) }
    SystemPermissionCard(
        title = stringResource(R.string.permissions_overlay),
        description = stringResource(R.string.permissions_overlay_help),
        status = stringResource(if (granted) R.string.permissions_granted else R.string.permissions_not_granted),
        tag = "permission-overlay",
        actionLabel = stringResource(R.string.permissions_system_settings),
        onAction = {
            open(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri()),
            )
        },
    )
}
