package com.helix.app.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.app.settings.QuickJsAccessService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
@Suppress("FunctionName")
internal fun QuickJsSettingsSection() {
    ScriptToolDelegationSettings()
    val context = LocalContext.current
    val settings = remember(context) { QuickJsAccessService(context) }
    val scope = rememberCoroutineScope()
    var enabled by remember(settings) { mutableStateOf<Boolean?>(null) }
    var confirming by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var readRevision by remember(settings) { mutableIntStateOf(0) }
    LaunchedEffect(settings, readRevision) {
        saving = true
        failed = false
        try {
            enabled = settings.enabled()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        } finally {
            saving = false
        }
    }

    fun save(value: Boolean) {
        if (saving) return
        saving = true
        failed = false
        confirming = false
        scope.launch {
            try {
                enabled = settings.setEnabled(value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true
            } finally {
                saving = false
            }
        }
    }
    Row {
        Text(stringResource(R.string.quickjs_native_title), Modifier.weight(1f))
        SettingsSwitch(enabled, onCheckedChange = {
            if (it) {
                confirming = true
            } else {
                save(false)
            }
        }, enabled = !saving)
    }
    if (failed) QuickJsSettingsReadFailure(!saving) { readRevision++ }
    Text(stringResource(R.string.quickjs_native_description))
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.quickjs_native_title)) },
            text = { Text(stringResource(R.string.quickjs_native_description)) },
            confirmButton = {
                TextButton(onClick = {
                    save(true)
                }) { Text(stringResource(R.string.quickjs_native_enable)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.quickjs_native_cancel)) }
            },
        )
    }
}

@Composable
@Suppress("FunctionName")
private fun ScriptToolDelegationSettings() {
    val context = LocalContext.current
    val settings = remember(context) { QuickJsAccessService(context) }
    val scope = rememberCoroutineScope()
    var enabled by remember(settings) { mutableStateOf<Boolean?>(null) }
    var saving by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var readRevision by remember(settings) { mutableIntStateOf(0) }
    LaunchedEffect(settings, readRevision) {
        saving = true
        failed = false
        try {
            enabled = settings.toolsEnabled()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        } finally {
            saving = false
        }
    }
    Row {
        Text(stringResource(R.string.script_tools_title), Modifier.weight(1f))
        SettingsSwitch(
            enabled,
            modifier =
                Modifier.testTag(
                    "settings-script-tool-bridge",
                ),
            enabled = !saving,
            onCheckedChange = { value ->
                saving = true
                failed = false
                scope.launch {
                    try {
                        enabled = settings.setToolsEnabled(value)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        failed = true
                    } finally {
                        saving = false
                    }
                }
            },
        )
    }
    Text(stringResource(R.string.script_tools_description))
    if (failed) QuickJsSettingsReadFailure(!saving) { readRevision++ }
}

@Composable
@Suppress("FunctionName")
private fun QuickJsSettingsReadFailure(
    enabled: Boolean,
    onRetry: () -> Unit,
) {
    Text(stringResource(R.string.quickjs_native_save_failed))
    TextButton(onClick = onRetry, enabled = enabled) { Text(stringResource(R.string.chat_retry)) }
}
