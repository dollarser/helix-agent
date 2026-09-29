package com.helix.app.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.runtime.quickjs.JsNativeAccessSettings

@Composable
@Suppress("FunctionName")
internal fun QuickJsSettingsSection() {
    val context = LocalContext.current
    val settings = remember(context) { JsNativeAccessSettings(context) }
    var enabled by remember { mutableStateOf(settings.enabled) }
    var confirming by remember { mutableStateOf(false) }
    Row {
        Text(stringResource(R.string.quickjs_native_title), Modifier.weight(1f))
        Switch(enabled, onCheckedChange = {
            if (it) {
                confirming = true
            } else {
                settings.setEnabled(false)
                enabled = settings.enabled
            }
        })
    }
    Text(stringResource(R.string.quickjs_native_description))
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.quickjs_native_title)) },
            text = { Text(stringResource(R.string.quickjs_native_description)) },
            confirmButton = {
                TextButton(onClick = {
                    settings.setEnabled(true)
                    enabled = settings.enabled
                    confirming = false
                }) { Text(stringResource(R.string.quickjs_native_enable)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.quickjs_native_cancel)) }
            },
        )
    }
}
