package com.helix.app.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
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
import androidx.compose.material3.MaterialTheme
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
import com.helix.app.ui.indicatedVerticalScroll

/** System grants only: never creates an Agent scope or an approval rule. */
@Composable
@Suppress("FunctionName", "LongMethod")
fun SystemPermissionsScreen(
    filePermissions: (@Composable () -> Unit)? = null,
    onFileLocations: (() -> Unit)? = null,
    devicePermissions: (@Composable () -> Unit)? = null,
    advancedPermissions: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val hasAllFilesPermission =
        remember(context) {
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
                .orEmpty()
                .contains(Manifest.permission.MANAGE_EXTERNAL_STORAGE)
        }
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
    val allFilesSupported = Build.VERSION.SDK_INT >= 30 && hasAllFilesPermission
    val allFiles = remember(revision) { if (allFilesSupported) Environment.isExternalStorageManager() else null }
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
            TextButton({ files = false }, Modifier.testTag("permission-files-back")) {
                Text(stringResource(R.string.permissions_back))
            }
            Box(Modifier.weight(1f)) { filePermissions() }
        }
        return
    }
    Column(
        Modifier
            .fillMaxSize()
            .indicatedVerticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("screen-permissions"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.permissions_help))
        PermissionGroupTitle(R.string.permissions_group_daily)
        PermissionEntry(
            R.string.permissions_notifications,
            R.string.permissions_notifications_help,
            notifications,
            "permission-notifications",
        ) {
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
        PermissionEntry(
            R.string.permissions_calendar,
            R.string.permissions_calendar_help,
            calendar,
            "permission-calendar",
        ) {
            if (calendar) appSettings() else calendarRequest.launch(Manifest.permission.WRITE_CALENDAR)
        }
        PermissionEntry(
            R.string.permissions_listener,
            R.string.permissions_listener_help,
            listener,
            "permission-listener",
            requestDirectly = false,
        ) {
            open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        FilePermissionEntries(
            allFiles = allFiles,
            onAllFiles = {
                open(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        "package:${context.packageName}".toUri(),
                    ),
                )
            },
            onFileLocations = onFileLocations,
            onConfigure = filePermissions?.let { { files = true } },
        )
        PermissionGroupTitle(R.string.permissions_group_apps)
        ApplicationVisibilityPermission(revision, appSettings)
        devicePermissions?.let {
            PermissionGroupTitle(R.string.permissions_group_device)
            it()
        }
        if (advancedPermissions != null) {
            SettingsDisclosure(stringResource(R.string.permissions_root_diagnostics), "permissions-root-details") {
                Text(
                    stringResource(R.string.permissions_root_diagnostics_help),
                    style = MaterialTheme.typography.bodySmall,
                )
                advancedPermissions()
            }
        }
        PermissionGroupTitle(R.string.permissions_group_other)
        OtherDeclaredPermissions(revision, { revision++ }, appSettings)
        TextButton(appSettings, Modifier.testTag("permission-app-settings")) {
            Text(stringResource(R.string.permissions_app_settings))
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
    description: Int,
    granted: Boolean,
    tag: String,
    requestDirectly: Boolean = true,
    action: () -> Unit,
) {
    SystemPermissionCard(
        title = stringResource(title),
        description = stringResource(description),
        status = stringResource(if (granted) R.string.permissions_granted else R.string.permissions_not_granted),
        tag = tag,
        actionLabel =
            stringResource(
                if (!granted && requestDirectly) R.string.permissions_request else R.string.permissions_system_settings,
            ),
        onAction = action,
    )
}

@Composable
@Suppress("FunctionName")
private fun PermissionGroupTitle(title: Int) {
    Text(
        stringResource(title),
        Modifier.padding(top = 12.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
@Suppress("FunctionName")
private fun FilePermissionEntries(
    allFiles: Boolean?,
    onAllFiles: () -> Unit,
    onFileLocations: (() -> Unit)?,
    onConfigure: (() -> Unit)?,
) {
    if (allFiles == null && onFileLocations == null && onConfigure == null) return
    PermissionGroupTitle(R.string.permissions_group_files)
    allFiles?.let {
        PermissionEntry(
            R.string.permissions_all_files,
            R.string.permissions_all_files_help,
            it,
            "permission-all-files",
            requestDirectly = false,
            action = onAllFiles,
        )
    }
    onFileLocations?.let {
        SystemPermissionCard(
            stringResource(R.string.permissions_file_locations),
            stringResource(R.string.permissions_file_locations_help),
            null,
            "permission-file-locations",
            stringResource(R.string.permissions_view_locations),
            onAction = it,
        )
    }
    onConfigure?.let {
        SystemPermissionCard(
            stringResource(R.string.permissions_files),
            stringResource(R.string.permissions_files_help),
            null,
            "permission-files",
            stringResource(R.string.permissions_configure),
            onAction = it,
        )
    }
}
