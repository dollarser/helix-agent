package com.helix.app.automation

import androidx.compose.material3.OutlinedButton
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
@Suppress("FunctionName")
internal fun MobileUseRootConnection() {
    val root =
        remember {
            com.helix.tools.deviceaccess.DeviceAccess
                .root("mobile-use")
        }
    var status by remember { mutableStateOf<com.helix.tools.root.RootAccessStatus?>(null) }
    var cachedGrant by remember { mutableStateOf<Boolean?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(root) {
        while (true) {
            withContext(Dispatchers.IO) { root?.let { it.status() to it.cachedAppGrant } }?.let {
                status = it.first
                cachedGrant = it.second
            }
            delay(750)
        }
    }
    val presentation =
        rootPermissionPresentation(status, cachedGrant)
    Text(stringResource(R.string.mobile_root_connection_title))
    Text(stringResource(presentation.connection), Modifier.testTag("automation-root-status"))
    Text(stringResource(R.string.mobile_root_connection_help))
    if (failed) Text(stringResource(R.string.permissions_settings_unavailable))
    OutlinedButton(
        onClick = {
            try {
                com.helix.tools.deviceaccess.DeviceAccess
                    .connectRoot("mobile-use")
                status = root?.status()
                failed = false
            } catch (_: RuntimeException) {
                failed = true
            }
        },
        enabled = root != null && cachedGrant == true && presentation.canConnect,
        modifier = Modifier.testTag("automation-root-authorize"),
    ) { Text(stringResource(R.string.mobile_root_connect)) }
    OutlinedButton(
        onClick = {
            root?.disconnect()
            status = root?.status()
        },
        modifier = Modifier.testTag("automation-root-disconnect"),
    ) { Text(stringResource(R.string.automation_root_disconnect)) }
}
