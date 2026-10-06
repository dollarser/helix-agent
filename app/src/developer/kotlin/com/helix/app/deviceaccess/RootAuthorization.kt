package com.helix.app.deviceaccess

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.tools.deviceaccess.DeviceAccess
import com.helix.tools.root.HelixRootService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
@Suppress("FunctionName")
internal fun RootAuthorization(context: Context) {
    val consumer = "helix-system-authorization"
    val access =
        remember(context) {
            DeviceAccess.configure(context, consumer, HelixRootService::class.java)
            requireNotNull(DeviceAccess.root(consumer))
        }
    var presentation by remember { mutableStateOf(rootGrantPresentation(access.status(), access.cachedAppGrant)) }
    var failed by remember { mutableStateOf(false) }
    var connectRequested by remember { mutableStateOf(false) }
    LaunchedEffect(access) {
        while (true) {
            presentation =
                withContext(Dispatchers.IO) { rootGrantPresentation(access.status(), access.cachedAppGrant) }
            if (connectRequested && access.status().grant != com.helix.tools.root.RootGrantState.REQUESTING) {
                connectRequested = false
                if (access.status().grant == com.helix.tools.root.RootGrantState.GRANTED) {
                    try {
                        DeviceAccess.connectEnabledRootConsumers()
                    } catch (_: IllegalStateException) {
                        failed = true
                    }
                }
            }
            delay(750)
        }
    }
    com.helix.app.ui.SystemPermissionCard(
        title = stringResource(R.string.device_access_root_title),
        description = stringResource(R.string.device_access_root_help),
        status = stringResource(presentation.label),
        tag = "permission-root-authorize",
        actionLabel = stringResource(R.string.device_access_root_authorize),
        enabled = presentation.canRequest,
    ) {
        try {
            DeviceAccess.requestRootFromUser(consumer)
            connectRequested = true
            failed = false
        } catch (_: RuntimeException) {
            failed = true
        }
    }
    if (failed) Text(stringResource(R.string.permissions_settings_unavailable))
}
