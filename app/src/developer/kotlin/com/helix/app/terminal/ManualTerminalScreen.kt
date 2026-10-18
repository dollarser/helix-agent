package com.helix.app.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R

@OptIn(ExperimentalLayoutApi::class)
@Composable
@Suppress("FunctionName")
internal fun ManualTerminalScreen(
    model: ManualTerminalViewModel,
    directory: String,
    onBack: () -> Unit,
    onRuntimeSettings: () -> Unit = {},
) {
    val state by model.state.collectAsState()
    LaunchedEffect(state.connection) { state.connection?.let { model.observe(it) } }
    var keyboard by remember(state.connection) { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.isImeVisible
    var imeWasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeVisible) {
            imeWasVisible = true
        } else if (imeWasVisible) {
            keyboard = false
            imeWasVisible = false
        }
    }
    Surface {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .testTag("manual-terminal-screen"),
        ) {
            TerminalTopBar(
                state = state,
                model = model,
                directory = directory,
                onBack = onBack,
                onInfo = {
                    keyboard = false
                    info = true
                },
                onHelp = {
                    keyboard = false
                    help = true
                },
                keyboardVisible = imeVisible || keyboard,
                onToggleKeyboard = { keyboard = !(imeVisible || keyboard) },
                onRuntimeSettings = onRuntimeSettings,
            )
            TerminalBody(state, directory, model, keyboard) { keyboard = true }
        }
    }
    if (info) TerminalInfo(state.session, directory) { info = false }
    if (help) {
        TerminalHelp(
            onDismiss = { help = false },
            onLicenses = {
                help = false
                licenses = true
            },
        )
    }
    if (licenses) TerminalLicenses { licenses = false }
}

@Composable
@Suppress("FunctionName")
private fun ColumnScope.TerminalBody(
    state: TerminalPageState,
    directory: String,
    model: ManualTerminalViewModel,
    keyboard: Boolean,
    onTerminalTap: () -> Unit,
) {
    TerminalStatusBanners(state.errorMessage, !state.isWriter && state.connection != null)
    if (state.failed) Text(stringResource(R.string.terminal_failed), Modifier.testTag("terminal-error"))
    val connection = state.connection
    if (connection != null) {
        ManualTerminalViewport(
            connection,
            keyboard && state.isWriter,
            Modifier.weight(1f),
            onEnded = { model.refresh() },
            onTerminalTap = onTerminalTap,
        )
    } else {
        TerminalEmptyState(state, directory, model)
    }
}

@Composable
@Suppress("FunctionName")
private fun ColumnScope.TerminalEmptyState(
    state: TerminalPageState,
    directory: String,
    model: ManualTerminalViewModel,
) {
    val message =
        when {
            state.session?.phase == "UNKNOWN" && state.session.canSettle -> R.string.terminal_runtime_ended
            state.session?.phase == "UNKNOWN" -> R.string.terminal_runtime_unknown
            state.session?.canSettle == true -> R.string.terminal_settlement_required
            state.hasSession -> R.string.terminal_detached
            else -> R.string.terminal_initial_help
        }
    Column(
        Modifier.weight(1f).padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(message))
        if (!state.hasSession) {
            TextButton(
                onClick = { model.open(directoryToStart = directory) },
                enabled = !state.busy,
                modifier = Modifier.testTag("terminal-start"),
            ) {
                Text(stringResource(R.string.terminal_start))
            }
        } else if (state.session?.canConnect == true) {
            TextButton(
                onClick = { model.open(directoryToStart = null) },
                enabled = !state.busy,
                modifier = Modifier.testTag("terminal-connect"),
            ) { Text(stringResource(R.string.terminal_connect)) }
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun TerminalStatusBanners(
    errorMessage: String?,
    isObserver: Boolean,
) {
    if (errorMessage != null) {
        val message =
            when (errorMessage) {
                "CAPACITY_FULL" -> stringResource(R.string.terminal_capacity_full)
                "CLOSE_UNCONFIRMED" -> stringResource(R.string.terminal_close_unconfirmed)
                else -> errorMessage
            }
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("terminal-capacity-error").padding(horizontal = 8.dp),
        )
    }
    if (isObserver) {
        Text(
            text = stringResource(R.string.terminal_observer_mode),
            color = MaterialTheme.colorScheme.tertiary,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.testTag("terminal-observer-banner").padding(horizontal = 8.dp),
        )
    }
}

@Composable
@Suppress("FunctionName")
private fun TerminalLicenses(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text =
        remember {
            listOf(
                "NOTICE.txt",
                "Apache-2.0.txt",
                "libvterm-MIT.txt",
                "Inconsolata-NOTICE.txt",
                "Inconsolata-OFL.txt",
            ).joinToString("\n\n") { name ->
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
