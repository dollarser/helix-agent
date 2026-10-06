package com.helix.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.helix.app.R

/** Permissions with dedicated, capability-specific controls on the same page. */
private val dedicatedPermissions =
    buildSet {
        add(Manifest.permission.WRITE_CALENDAR)
        add(Manifest.permission.SYSTEM_ALERT_WINDOW)
        add("moe.shizuku.manager.permission.API_V23")
        if (Build.VERSION.SDK_INT >= 30) {
            add(Manifest.permission.MANAGE_EXTERNAL_STORAGE)
            add(Manifest.permission.QUERY_ALL_PACKAGES)
        }
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

@Composable
@Suppress("FunctionName")
internal fun ApplicationVisibilityPermission(
    revision: Int,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val broadVisibility =
        remember(revision) {
            Build.VERSION.SDK_INT >= 30 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.QUERY_ALL_PACKAGES) ==
                PackageManager.PERMISSION_GRANTED
        }
    SystemPermissionCard(
        title = stringResource(R.string.permissions_app_list),
        description = stringResource(R.string.permissions_app_list_help),
        status =
            stringResource(
                when {
                    Build.VERSION.SDK_INT < 30 -> R.string.permissions_app_list_legacy
                    broadVisibility -> R.string.permissions_app_list_broad
                    else -> R.string.permissions_app_list_filtered
                },
            ),
        tag = "permission-app-list",
        actionLabel = stringResource(R.string.permissions_system_settings),
        onAction = onSettings,
    )
}

/** Uses the installed manifest, so newly declared runtime permissions are not silently omitted. */
@Composable
@Suppress("FunctionName", "LongMethod")
internal fun OtherDeclaredPermissions(
    revision: Int,
    onChanged: () -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val packageManager = context.packageManager
    val permissions =
        remember(packageManager) {
            packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
                .orEmpty()
                .filterNot { it in dedicatedPermissions || it.startsWith("${context.packageName}.") }
                .filterNot {
                    Build.VERSION.SDK_INT > 29 && it in
                        setOf(
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        )
                }.map { name ->
                    val info =
                        try {
                            packageManager.getPermissionInfo(name, 0)
                        } catch (_: PackageManager.NameNotFoundException) {
                            null
                        }
                    name to info
                }
        }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onChanged() }
    val (runtimePermissions, otherPermissions) =
        permissions.partition { (_, info) ->
            info?.protectionLevel?.and(PermissionInfo.PROTECTION_MASK_BASE) == PermissionInfo.PROTECTION_DANGEROUS
        }
    runtimePermissions.forEach { (name, info) ->
        val granted =
            remember(name, revision) {
                ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED
            }
        SystemPermissionCard(
            title = info?.loadLabel(packageManager)?.toString() ?: name,
            description = stringResource(R.string.permissions_runtime_help),
            status =
                stringResource(
                    if (granted) R.string.permissions_granted else R.string.permissions_not_granted,
                ),
            tag = "permission-declared-$name",
            actionLabel =
                stringResource(
                    if (granted) R.string.permissions_system_settings else R.string.permissions_request,
                ),
            onAction = { if (granted) onSettings() else request.launch(name) },
        )
    }
    SettingsDisclosure(stringResource(R.string.permissions_declared_title), "permissions-declared") {
        Text(stringResource(R.string.permissions_declared_help), style = MaterialTheme.typography.bodySmall)
        otherPermissions.forEach { (name, info) ->
            val granted =
                remember(name, revision) {
                    ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED
                }
            Text(info?.loadLabel(packageManager)?.toString() ?: name, style = MaterialTheme.typography.titleSmall)
            Text(name, style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(
                    when {
                        info == null -> R.string.permissions_status_unknown
                        granted -> R.string.permissions_install_granted
                        else -> R.string.permissions_system_restricted
                    },
                ),
                Modifier.testTag("permission-declared-$name-status"),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
