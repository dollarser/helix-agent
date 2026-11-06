package com.helix.app.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationManagerCompat
import com.helix.app.R

/** Actual installed capabilities and current system state; never starts a service or grants Agent scope. */
@Composable
@Suppress("FunctionName")
internal fun BackgroundSystemPermissions(
    revision: Int,
    declared: Set<String>,
    onChanged: () -> Unit,
    open: (Intent) -> Unit,
) {
    val context = LocalContext.current
    var requested by rememberSaveable { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onChanged() }
    val runtimeGranted =
        remember(revision) {
            Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    val canRequest = Build.VERSION.SDK_INT >= 33 && !runtimeGranted && !requested
    val notifications = remember(revision) { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    if (Build.VERSION.SDK_INT < 33 || Manifest.permission.POST_NOTIFICATIONS in declared) {
        SystemPermissionCard(
            stringResource(R.string.permissions_notifications),
            stringResource(R.string.permissions_notifications_help),
            stringResource(if (notifications) R.string.permissions_granted else R.string.permissions_not_granted),
            "permission-notifications",
            stringResource(if (canRequest) R.string.permissions_request else R.string.permissions_system_settings),
        ) {
            if (canRequest) {
                requested = true
                request.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                open(
                    Intent(
                        Settings.ACTION_APP_NOTIFICATION_SETTINGS,
                    ).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            }
        }
    }
    if (Manifest.permission.FOREGROUND_SERVICE in declared) {
        val exempt =
            remember(revision) {
                context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
            }
        SystemPermissionCard(
            stringResource(R.string.permissions_battery),
            stringResource(R.string.permissions_battery_help),
            stringResource(if (exempt) R.string.permissions_battery_exempt else R.string.permissions_battery_optimized),
            "permission-battery",
            stringResource(R.string.permissions_system_settings),
        ) {
            // The general settings list needs no direct-exemption permission; the user chooses the app.
            open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
