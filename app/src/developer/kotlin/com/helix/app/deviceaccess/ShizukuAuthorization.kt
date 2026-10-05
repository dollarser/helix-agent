package com.helix.app.deviceaccess

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.tools.deviceaccess.DeviceAccess
import com.helix.tools.deviceaccess.ShizukuPermissionActivity
import kotlinx.coroutines.delay

@Composable
@Suppress("FunctionName")
internal fun ShizukuAuthorization(context: Context) {
    var ready by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(context) {
        while (true) {
            ready = DeviceAccess.shizukuReady()
            granted = DeviceAccess.shizukuGranted()
            delay(750)
        }
    }
    Column(Modifier.testTag("permission-shizuku")) {
        com.helix.app.ui.SystemPermissionCard(
            title = stringResource(R.string.device_access_shizuku_title),
            description = stringResource(R.string.device_access_shizuku_help),
            status =
                stringResource(
                    when {
                        !ready -> R.string.device_access_shizuku_unavailable
                        granted -> R.string.permissions_granted
                        else -> R.string.permissions_not_granted
                    },
                ),
            tag = "permission-shizuku-manage",
            actionLabel =
                stringResource(
                    if (ready && !granted) R.string.permissions_request else R.string.device_access_shizuku_open,
                ),
        ) {
            try {
                val intent =
                    if (ready && !granted) {
                        Intent(context, ShizukuPermissionActivity::class.java)
                    } else {
                        requireNotNull(context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api"))
                    }
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                failed = false
            } catch (_: RuntimeException) {
                failed = true
            }
        }
        if (failed) Text(stringResource(R.string.permissions_settings_unavailable))
    }
}
