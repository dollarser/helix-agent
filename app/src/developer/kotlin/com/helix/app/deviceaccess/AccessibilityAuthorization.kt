package com.helix.app.deviceaccess

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.helix.app.R
import com.helix.tools.deviceaccess.DeviceAccess

@Composable
@Suppress("FunctionName")
internal fun AccessibilityAuthorization(context: Context) {
    var revision by remember { mutableStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { revision++ }
    val components =
        remember(context, revision) {
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_SERVICES)
                .services
                .orEmpty()
                .filter { it.permission == Manifest.permission.BIND_ACCESSIBILITY_SERVICE }
                .map { ComponentName(context.packageName, it.name) }
        }
    val granted = remember(components, revision) { components.count { DeviceAccess.accessibilityEnabled(context, it) } }
    com.helix.app.ui.SystemPermissionCard(
        title = stringResource(R.string.device_access_accessibility_title),
        description = stringResource(R.string.device_access_accessibility_help),
        status = stringResource(R.string.device_access_accessibility_status, granted, components.size),
        tag = "permission-accessibility",
        actionLabel = stringResource(R.string.permissions_system_settings),
    ) {
        try {
            context.startActivity(DeviceAccess.accessibilitySettingsIntent())
            failed = false
        } catch (_: RuntimeException) {
            failed = true
        }
    }
    if (failed) Text(stringResource(R.string.permissions_settings_unavailable))
}
