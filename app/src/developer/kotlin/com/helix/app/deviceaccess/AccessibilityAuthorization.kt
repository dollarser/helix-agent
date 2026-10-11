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
import com.helix.app.R
import com.helix.tools.deviceaccess.DeviceAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
@Suppress("FunctionName")
internal fun AccessibilityAuthorization(context: Context) {
    var failed by remember { mutableStateOf(false) }
    var presentation by remember { mutableStateOf<AccessibilityPresentation?>(null) }
    var checkFailed by remember { mutableStateOf(false) }
    com.helix.app.ui.ForegroundRefresh(context) {
        try {
            presentation =
                withContext(Dispatchers.IO) {
                    val components =
                        context.packageManager
                            .getPackageInfo(context.packageName, PackageManager.GET_SERVICES)
                            .services
                            .orEmpty()
                            .filter { it.permission == Manifest.permission.BIND_ACCESSIBILITY_SERVICE }
                            .map { ComponentName(context.packageName, it.name) }
                    accessibilityPresentation(
                        components.count { DeviceAccess.accessibilityEnabled(context, it) },
                        components.count { DeviceAccess.accessibilityConnected(context, it) },
                        components.size,
                    )
                }
            checkFailed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            presentation = null
            checkFailed = true
        }
    }
    com.helix.app.ui.SystemPermissionCard(
        title = stringResource(R.string.device_access_accessibility_title),
        description = stringResource(presentation?.help ?: R.string.device_access_accessibility_help),
        status =
            stringResource(
                presentation?.status ?: when {
                    checkFailed -> R.string.device_access_status_failed
                    else -> R.string.device_access_status_checking
                },
            ),
        tag = "permission-accessibility",
        actionLabel = stringResource(presentation?.action ?: R.string.permissions_system_settings),
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
