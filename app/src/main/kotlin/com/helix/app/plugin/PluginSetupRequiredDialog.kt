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
import com.helix.app.plugin.PluginSetupReason
import com.helix.app.ui.indicatedVerticalScroll

internal fun pluginSetupExplanation(error: PluginSetupReason): Int =
    when (error) {
        PluginSetupReason.MOBILE_SCOPE -> R.string.plugin_setup_mobile_scope
        PluginSetupReason.MOBILE_BACKEND -> R.string.plugin_setup_mobile_backend
        PluginSetupReason.CONNECTION -> R.string.plugin_setup_connection
        PluginSetupReason.HOST_UNAVAILABLE -> R.string.plugin_setup_host
        PluginSetupReason.UNSUPPORTED -> R.string.plugin_setup_unsupported
        PluginSetupReason.RELOAD -> R.string.plugin_setup_reload
        else -> R.string.plugin_setup_generic
    }

/** Explanation and navigation only; neither button grants permission or selects a plugin. */
@Composable
@Suppress("FunctionName")
internal fun PluginSetupRequiredDialog(
    error: PluginSetupReason,
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
                Text(
                    stringResource(
                        if (error == PluginSetupReason.MOBILE_BACKEND) {
                            R.string.mobile_use_go_authorize
                        } else {
                            R.string.plugin_setup_continue
                        },
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onDismiss, Modifier.testTag("plugin-setup-dismiss")) {
                Text(
                    stringResource(
                        if (error == PluginSetupReason.MOBILE_BACKEND) {
                            R.string.common_cancel
                        } else {
                            R.string.plugin_setup_later
                        },
                    ),
                )
            }
        },
    )
}
