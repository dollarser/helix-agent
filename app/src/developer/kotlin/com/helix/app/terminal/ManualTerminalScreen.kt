package com.helix.app.terminal

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.helix.app.R

@Composable
@Suppress("FunctionName", "LongMethod")
internal fun ManualTerminalScreen(
    model: ManualTerminalViewModel,
    directory: String,
    onBack: () -> Unit,
) {
    val state by model.state.collectAsState()
    LaunchedEffect(state.connection) { state.connection?.let { model.observe(it) } }
    var keyboard by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    Surface {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .testTag("manual-terminal-screen"),
        ) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.files_back)) }
                TextButton(onClick = { licenses = true }) { Text(stringResource(R.string.terminal_licenses)) }
                TextButton(
                    onClick = {
                        keyboard = !keyboard
                    },
                    modifier =
                        Modifier.testTag(
                            "terminal-keyboard",
                        ),
                ) { Text(stringResource(R.string.terminal_keyboard)) }
            }
            Text(stringResource(R.string.terminal_title), style = MaterialTheme.typography.titleMedium)
            val initialDirectory =
                state.session
                    ?.workspace
                    ?.substringAfter("/workspaces/app", directory)
                    ?.trimStart('/')
                    ?.ifBlank { "." } ?: directory
            Text(
                stringResource(R.string.terminal_directory, initialDirectory),
                Modifier.testTag("terminal-workspace"),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(stringResource(R.string.terminal_shared_workspace), style = MaterialTheme.typography.bodySmall)
            state.session?.let {
                Text(
                    "${it.phase} · ${it.stopReason.orEmpty()} · ${it.exitStatus ?: "—"}",
                    Modifier.testTag("terminal-state"),
                )
            }
            if (state.failed) Text(stringResource(R.string.terminal_failed), Modifier.testTag("terminal-error"))
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                if (!state.hasSession) {
                    TextButton(
                        onClick = { model.open(directoryToStart = directory) },
                        enabled = !state.busy,
                        modifier = Modifier.testTag("terminal-start"),
                    ) {
                        Text(stringResource(R.string.terminal_start))
                    }
                } else {
                    TextButton(
                        onClick = {
                            model.open(directoryToStart = null)
                        },
                        enabled = !state.busy && state.connection == null,
                        modifier =
                            Modifier.testTag(
                                "terminal-connect",
                            ),
                    ) {
                        Text(stringResource(R.string.terminal_connect))
                    }
                    TextButton(
                        onClick = { model.stop() },
                        enabled = !state.busy,
                        modifier = Modifier.testTag("terminal-stop"),
                    ) {
                        Text(stringResource(R.string.terminal_stop))
                    }
                    TextButton(
                        onClick = {
                            model.settle()
                        },
                        enabled = !state.busy && state.session?.canSettle == true,
                        modifier =
                            Modifier.testTag(
                                "terminal-settle",
                            ),
                    ) {
                        Text(stringResource(R.string.terminal_settle))
                    }
                }
                TextButton(
                    onClick = { model.refresh() },
                    enabled = !state.busy,
                    modifier = Modifier.testTag("terminal-refresh"),
                ) {
                    Text(stringResource(R.string.terminal_refresh))
                }
            }
            val connection = state.connection
            if (connection != null) {
                ManualTerminalViewport(connection, keyboard, Modifier.weight(1f), onEnded = { model.refresh() })
            } else {
                Text(stringResource(R.string.terminal_detached), Modifier.padding(8.dp))
            }
        }
    }
    if (licenses) TerminalLicenses { licenses = false }
}

@Composable
@Suppress("FunctionName")
private fun TerminalLicenses(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text =
        remember {
            listOf("NOTICE.txt", "Apache-2.0.txt", "libvterm-MIT.txt").joinToString("\n\n") { name ->
                context.assets
                    .open("terminal-licenses/$name")
                    .bufferedReader()
                    .use { it.readText() }
            }
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.terminal_licenses)) },
        text = {
            androidx.compose.foundation.text.selection.SelectionContainer {
                Column(Modifier.verticalScroll(rememberScrollState())) { Text(text) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.files_close)) } },
    )
}
