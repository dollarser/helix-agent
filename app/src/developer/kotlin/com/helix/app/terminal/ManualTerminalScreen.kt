package com.helix.app.terminal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.ui.indicatedHorizontalScroll

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
                onBack = onBack,
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
    TerminalTabBar(
        sessions = state.sessions,
        activeSessionId = state.activeSessionId,
        busy = state.busy,
        directory = directory,
        onSwitch = { model.switchSession(it) },
        onNewTab = { model.open(directoryToStart = it) },
    )
    TerminalStatusBanners(state.errorMessage, !state.isWriter && state.connection != null)
    TerminalSessionDetails(state.session, directory, state.failed)
    TerminalActionControls(state, directory, model)
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
        TerminalEmptyState(state)
    }
}

@Composable
@Suppress("FunctionName")
private fun TerminalEmptyState(state: TerminalPageState) {
    val message =
        when {
            state.session?.phase == "UNKNOWN" && state.session.canSettle -> R.string.terminal_runtime_ended
            state.session?.phase == "UNKNOWN" -> R.string.terminal_runtime_unknown
            state.session?.canSettle == true -> R.string.terminal_settlement_required
            state.hasSession -> R.string.terminal_detached
            else -> R.string.terminal_initial_help
        }
    Text(
        stringResource(message),
        Modifier.padding(8.dp),
    )
}

@Composable
@Suppress("FunctionName")
private fun TerminalTabBar(
    sessions: List<ManualTerminal.State>,
    activeSessionId: String?,
    busy: Boolean,
    directory: String,
    onSwitch: (String) -> Unit,
    onNewTab: (String) -> Unit,
) {
    if (sessions.isEmpty()) return
    Row(Modifier.indicatedHorizontalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
        sessions.forEachIndexed { index, sess ->
            val active = sess.sessionId == activeSessionId
            TextButton(
                onClick = { onSwitch(sess.sessionId) },
                modifier = Modifier.testTag("terminal-tab-$index"),
            ) {
                Text(
                    text = stringResource(R.string.terminal_tab_title, index + 1),
                    style = if (active) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (sessions.size < 2) {
            TextButton(
                onClick = { onNewTab(directory) },
                enabled = !busy,
                modifier = Modifier.testTag("terminal-new-session"),
            ) {
                Text(stringResource(R.string.terminal_new_tab))
            }
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
private fun TerminalSessionDetails(
    session: ManualTerminal.State?,
    directory: String,
    failed: Boolean,
) {
    val initialDirectory =
        session
            ?.workspace
            ?.substringAfter("/workspaces/app", directory)
            ?.trimStart('/')
            ?.ifBlank { "." } ?: directory
    Text(
        stringResource(R.string.terminal_directory, initialDirectory),
        Modifier.padding(horizontal = 12.dp, vertical = 2.dp).testTag("terminal-workspace"),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    session?.let {
        val status =
            when {
                it.phase == "UNKNOWN" && it.canSettle -> stringResource(R.string.terminal_state_ended)
                it.phase == "UNKNOWN" -> stringResource(R.string.terminal_state_unknown)
                else -> listOfNotNull(it.phase, it.stopReason, it.exitStatus?.toString()).joinToString(" · ")
            }
        Text(
            status,
            Modifier.padding(horizontal = 12.dp, vertical = 2.dp).testTag("terminal-state"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (session?.canSettle == true && session.phase != "UNKNOWN") {
        Text(stringResource(R.string.terminal_settlement_required), Modifier.testTag("terminal-settlement-required"))
    }
    if (failed) Text(stringResource(R.string.terminal_failed), Modifier.testTag("terminal-error"))
}

@Composable
@Suppress("FunctionName")
private fun TerminalActionControls(
    state: TerminalPageState,
    directory: String,
    model: ManualTerminalViewModel,
) {
    Row(Modifier.indicatedHorizontalScroll(rememberScrollState())) {
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
                onClick = { model.settle() },
                enabled = !state.busy,
                modifier = Modifier.testTag("terminal-settle"),
            ) { Text(stringResource(R.string.terminal_settle)) }
        }
        if (state.connection == null && state.session?.canConnect == true) {
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
