package com.helix.app.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.app.settings.QuickJsAccessService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
@Suppress("FunctionName")
internal fun QuickJsSettingsSection() {
    val context = LocalContext.current
    val settings = remember(context) { QuickJsAccessService(context) }
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(settings) {
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
        Switch(enabled, onCheckedChange = {
            if (it) {
                confirming = true
            } else {
                save(false)
            }
        }, enabled = !saving)
    }
    if (failed) Text(stringResource(R.string.quickjs_native_save_failed))
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
