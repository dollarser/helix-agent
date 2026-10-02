package com.helix.app.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.ui.indicatedVerticalScroll

/** Persistent explanatory text is available on demand; live errors remain on the terminal page. */
@Composable
@Suppress("FunctionName")
internal fun TerminalHelp(
    onDismiss: () -> Unit,
    onLicenses: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.terminal_help)) },
        text = {
            Column(
                Modifier
                    .heightIn(
                        max = 420.dp,
                    ).indicatedVerticalScroll(rememberScrollState())
                    .testTag("terminal-help-content"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.terminal_initial_help))
                Text(stringResource(R.string.terminal_shared_workspace))
                Text(stringResource(R.string.terminal_execution_busy_help))
                Text(stringResource(R.string.terminal_help_input))
                Text(stringResource(R.string.terminal_help_font))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("terminal-help-close")) {
                Text(stringResource(R.string.chat_details_close))
            }
        },
        dismissButton = { TextButton(onClick = onLicenses) { Text(stringResource(R.string.terminal_licenses)) } },
    )
}
