package com.helix.app.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import com.helix.app.R
import com.helix.app.files.FileManagerService
import com.helix.app.files.SharedStorageAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
@Suppress("FunctionName", "TooGenericExceptionCaught", "SwallowedException")
internal fun rememberSharedStorageNavigation(
    state: FilesScreenState,
    fileManager: FileManagerService,
): (String) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val permissionRequired = stringResource(R.string.files_shared_permission_required)
    val loadFailed = stringResource(R.string.files_read_directory_error)
    val access = remember(context) { SharedStorageAccess(context.applicationContext) }
    var pendingPath by rememberSaveable { mutableStateOf("") }
    val enter: () -> Unit = {
        if (access.isGranted()) {
            val path = pendingPath
            scope.launch {
                try {
                    val sources = withContext(Dispatchers.IO) { fileManager.sources() }
                    state.replaceSources(sources)
                    if (sources.any { it.scopeId == SharedStorageAccess.SCOPE_ID }) {
                        state.status = null
                        state.openLocation(SharedStorageAccess.SCOPE_ID, path)
                        state.reloadTick++
                    } else {
                        state.status = permissionRequired
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    state.status = loadFailed
                }
            }
        } else {
            state.status = permissionRequired
        }
    }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { enter() }
    val preAndroid11Permissions =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { enter() }
    return { path ->
        pendingPath = path
        if (access.isWritable()) {
            enter()
        } else if (Build.VERSION.SDK_INT >= 30) {
            val intent =
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    "package:${context.packageName}".toUri(),
                )
            try {
                settings.launch(intent)
            } catch (_: ActivityNotFoundException) {
                settings.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            preAndroid11Permissions.launch(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
            )
        }
    }
}
