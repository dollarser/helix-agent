package com.helix.app.plugin

import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.app.ui.indicatedVerticalScroll

internal fun pluginSetupExplanation(error: String): Int =
    when (error) {
        "MOBILE_USE_NOT_CONFIGURED" -> R.string.plugin_setup_mobile_scope
        "MOBILE_USE_BACKEND_UNAVAILABLE" -> R.string.plugin_setup_mobile_backend
        else -> R.string.plugin_setup_generic
    }

/** Explanation and navigation only; neither button grants permission or selects a plugin. */
@Composable
@Suppress("FunctionName")
internal fun PluginSetupRequiredDialog(
    error: String,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.plugin_setup_title)) },
        text = {
            Text(
                stringResource(pluginSetupExplanation(error)),
                Modifier.indicatedVerticalScroll(rememberScrollState()).testTag("plugin-setup-explanation"),
            )
        },
        confirmButton = {
            TextButton(onContinue, Modifier.testTag("plugin-setup-continue")) {
                Text(stringResource(R.string.plugin_setup_continue))
            }
        },
        dismissButton = {
            TextButton(onDismiss, Modifier.testTag("plugin-setup-dismiss")) {
                Text(stringResource(R.string.plugin_setup_later))
            }
        },
    )
}
