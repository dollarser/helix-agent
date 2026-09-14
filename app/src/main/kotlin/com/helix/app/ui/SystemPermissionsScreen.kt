package com.helix.app.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.helix.app.R

/** System grants only: never creates an Agent scope or an approval rule. */
@Composable
@Suppress("FunctionName", "LongMethod")
fun SystemPermissionsScreen(filePermissions: (@Composable () -> Unit)? = null) {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    var unavailable by remember { mutableStateOf(false) }
    var files by rememberSaveable { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { revision++ }
    val notificationRequest =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { revision++ }
    val calendarRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { revision++ }
    val notifications = remember(revision) { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    val calendar =
        remember(revision) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED
        }
    val listener =
        remember(revision) {
            context.packageName in
                NotificationManagerCompat.getEnabledListenerPackages(context)
        }
    val open: (Intent) -> Unit = { intent ->
        try {
            context.startActivity(intent)
            unavailable = false
        } catch (_: ActivityNotFoundException) {
            unavailable = true
        }
    }
    val appSettings = {
        open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
    }
    BackHandler(files) { files = false }
    if (files && filePermissions != null) {
        Column(Modifier.fillMaxSize()) {
            TextButton({ files = false }) { Text(stringResource(R.string.permissions_back)) }
            Box(Modifier.weight(1f)) { filePermissions() }
        }
        return
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("screen-permissions"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.permissions_help))
        PermissionEntry(R.string.permissions_notifications, notifications, "permission-notifications") {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                open(
                    Intent(
                        Settings.ACTION_APP_NOTIFICATION_SETTINGS,
                    ).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            }
        }
        PermissionEntry(R.string.permissions_calendar, calendar, "permission-calendar") {
            if (calendar) appSettings() else calendarRequest.launch(Manifest.permission.WRITE_CALENDAR)
        }
        PermissionEntry(R.string.permissions_listener, listener, "permission-listener") {
            open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        TextButton(appSettings, Modifier.testTag("permission-app-settings")) {
            Text(stringResource(R.string.permissions_app_settings))
        }
        if (filePermissions != null) {
            OutlinedButton({ files = true }, Modifier.testTag("permission-files")) {
                Text(stringResource(R.string.permissions_files))
            }
        }
        if (unavailable) {
            Text(
                stringResource(R.string.permissions_settings_unavailable),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun PermissionEntry(
    title: Int,
    granted: Boolean,
    tag: String,
    action: () -> Unit,
) {
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(if (granted) R.string.permissions_granted else R.string.permissions_not_granted),
        Modifier.testTag("$tag-status"),
    )
    OutlinedButton(action, Modifier.testTag(tag)) { Text(stringResource(R.string.permissions_manage)) }
    HorizontalDivider()
}
