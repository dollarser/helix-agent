package com.helix.app.automation

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
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
import com.helix.app.automation.shizuku.ShizukuAutomationBackend
import com.helix.app.automation.shizuku.ShizukuPermissionActivity
import com.helix.tools.automation.AutomationBackendState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
@Suppress("FunctionName")
internal fun ShizukuSettings(context: Context) {
    val backend = remember(context) { ShizukuAutomationBackend(context) }
    var state by remember { mutableStateOf(AutomationBackendState.UNAVAILABLE) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(backend) {
        while (true) {
            state = withContext(Dispatchers.IO) { backend.state() }
            delay(750)
        }
    }
    Column(Modifier.testTag("automation-shizuku")) {
        RootBackendStatus()
        Text(stringResource(R.string.automation_shizuku_description))
        Text(
            stringResource(
                when (state) {
                    AutomationBackendState.UNAVAILABLE -> R.string.automation_shizuku_unavailable
                    AutomationBackendState.PERMISSION_REQUIRED -> R.string.automation_shizuku_permission
                    AutomationBackendState.READY -> R.string.automation_shizuku_ready
                    AutomationBackendState.LOST -> R.string.automation_shizuku_lost
                },
            ),
            Modifier.testTag("automation-shizuku-status"),
        )
        if (failed) Text(stringResource(R.string.automation_shizuku_open_failed))
        OutlinedButton(onClick = {
            try {
                val intent =
                    if (state == AutomationBackendState.PERMISSION_REQUIRED) {
                        Intent(context, ShizukuPermissionActivity::class.java)
                    } else {
                        requireNotNull(context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api"))
                    }
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                failed = false
            } catch (_: RuntimeException) {
                failed = true
            }
        }, modifier = Modifier.testTag("automation-shizuku-authorize")) {
            Text(
                stringResource(
                    if (state == AutomationBackendState.PERMISSION_REQUIRED) {
                        R.string.automation_shizuku_authorize
                    } else {
                        R.string.automation_shizuku_manage
                    },
                ),
            )
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun RootBackendStatus() {
    val root =
        remember {
            com.helix.app.automation.shizuku.MobileUseRootConnection
                .access()
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
    val presentation = rootPermissionPresentation(status, cachedGrant)
    Text(stringResource(R.string.automation_backend_priority))
    Text(stringResource(presentation.authorization), Modifier.testTag("automation-root-grant"))
    Text(stringResource(presentation.connection), Modifier.testTag("automation-root-status"))
    Text(stringResource(R.string.mobile_root_connection_help))
    if (failed) Text(stringResource(R.string.permissions_settings_unavailable))
    OutlinedButton(
        onClick = {
            try {
                checkNotNull(root).requestRoot()
                status = root.status()
                failed = false
            } catch (_: RuntimeException) {
                failed = true
            }
        },
        enabled = root != null && presentation.canConnect,
        modifier = Modifier.testTag("automation-root-authorize"),
    ) { Text(stringResource(presentation.action)) }
    OutlinedButton(
        onClick = {
            root?.disconnect()
            status = root?.status()
        },
        modifier = Modifier.testTag("automation-root-disconnect"),
    ) { Text(stringResource(R.string.automation_root_disconnect)) }
}
