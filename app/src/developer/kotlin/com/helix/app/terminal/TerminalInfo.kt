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

/** Launch mapping is metadata, not the live cwd of the retained shell. */
@Composable
@Suppress("FunctionName")
internal fun TerminalInfo(
    session: ManualTerminal.State?,
    directory: String,
    onDismiss: () -> Unit,
) {
    val initialDirectory =
        session
            ?.workspace
            ?.substringAfter("/workspaces/app", directory)
            ?.trimStart('/')
            ?.ifBlank { "." }
            ?: directory
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.terminal_info)) },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).indicatedVerticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                session?.let { Text(terminalStatus(it), Modifier.testTag("terminal-state")) }
                Text(
                    stringResource(R.string.terminal_directory, initialDirectory),
                    Modifier.testTag("terminal-workspace"),
                )
                Text(stringResource(R.string.terminal_help_directory))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("terminal-info-close")) {
                Text(stringResource(R.string.chat_details_close))
            }
        },
    )
}

@Composable
internal fun terminalStatus(session: ManualTerminal.State): String = stringResource(terminalStatusResource(session))

internal fun terminalStatusResource(session: ManualTerminal.State): Int =
    when {
        session.phase == "UNKNOWN" && session.canSettle -> R.string.terminal_state_ended
        session.phase == "UNKNOWN" -> R.string.terminal_phase_unknown
        session.canSettle || session.phase == "STOPPED" -> R.string.terminal_phase_stopped
        session.phase == "RUNNING" -> R.string.terminal_state_running
        session.phase == "STARTING" -> R.string.terminal_phase_starting
        session.phase == "CLOSING" -> R.string.terminal_phase_closing
        else -> R.string.terminal_phase_unknown
    }
