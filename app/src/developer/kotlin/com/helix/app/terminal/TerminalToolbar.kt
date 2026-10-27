package com.helix.app.terminal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.helix.app.R

@Composable
@Suppress("FunctionName", "LongParameterList")
internal fun TerminalTopBar(
    state: TerminalPageState,
    model: ManualTerminalViewModel,
    directory: String,
    onBack: () -> Unit,
    onInfo: () -> Unit,
    onHelp: () -> Unit,
    keyboardVisible: Boolean,
    onToggleKeyboard: () -> Unit,
    onRuntimeSettings: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("terminal-toolbar"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.testTag("terminal-back")) {
            Icon(painterResource(R.drawable.ic_terminal_back), stringResource(R.string.terminal_leave))
        }
        TerminalSelector(state, model, directory, Modifier.weight(1f))
        IconButton(
            onClick = onToggleKeyboard,
            enabled = state.connection != null && state.isWriter,
            modifier = Modifier.testTag("terminal-keyboard"),
        ) {
            val label = if (keyboardVisible) R.string.terminal_hide_keyboard else R.string.terminal_show_keyboard
            Icon(painterResource(R.drawable.ic_terminal_keyboard), stringResource(label))
        }
        IconButton(
            onClick = onClose,
            enabled = state.activeSessionId != null && !state.busy,
            modifier = Modifier.testTag("terminal-settle"),
        ) {
            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.terminal_stop_close))
        }
        TerminalOptions(state.busy, model, onInfo, onRuntimeSettings, onHelp)
    }
}

@Composable
@Suppress("FunctionName")
private fun TerminalOptions(
    busy: Boolean,
    model: ManualTerminalViewModel,
    onInfo: () -> Unit,
    onRuntimeSettings: () -> Unit,
    onHelp: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton({ menu = true }, Modifier.testTag("terminal-menu")) {
            Icon(painterResource(R.drawable.ic_chat_more), stringResource(R.string.terminal_menu))
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            val items =
                listOf(
                    Triple(R.string.terminal_info, "terminal-info", onInfo),
                    Triple(R.string.terminal_clear_display, "terminal-clear-display") { model.clearDisplay() },
                    Triple(R.string.setup_runtime_title, "terminal-runtime-settings", onRuntimeSettings),
                    Triple(R.string.terminal_help, "terminal-help", onHelp),
                )
            items.forEach { (label, tag, action) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    onClick = {
                        menu = false
                        action()
                    },
                    enabled = !busy || tag == "terminal-info" || tag == "terminal-help",
                    modifier = Modifier.testTag(tag),
                )
            }
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun TerminalSelector(
    state: TerminalPageState,
    model: ManualTerminalViewModel,
    directory: String,
    modifier: Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val active = state.sessions.find { it.sessionId == state.activeSessionId } ?: state.session
    Box(modifier) {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("terminal-selector"),
        ) {
            Text(
                text = terminalDisplayTitle(active),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(painterResource(R.drawable.ic_expand_summary), null, Modifier.size(16.dp))
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            state.sessions.forEachIndexed { index, session ->
                val active = session.sessionId == state.activeSessionId
                DropdownMenuItem(
                    text = {
                        androidx.compose.foundation.layout.Column {
                            Text(terminalDisplayTitle(session))
                            Text(terminalStatus(session), style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    trailingIcon = {
                        if (active) Icon(painterResource(R.drawable.ic_check), null)
                    },
                    onClick = {
                        expanded = false
                        if (!active) model.switchSession(session.sessionId)
                    },
                    enabled = !state.busy,
                    modifier = Modifier.testTag("terminal-tab-$index").semantics { selected = active },
                )
            }
            run {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.terminal_new_tab)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_composer_add), null) },
                    trailingIcon = {
                        Text(
                            stringResource(R.string.terminal_parallel_limit, state.maxSessions),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    onClick = {
                        expanded = false
                        model.open(directoryToStart = directory)
                    },
                    enabled = !state.busy,
                    modifier = Modifier.testTag("terminal-new-session"),
                )
            }
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun terminalDisplayTitle(session: ManualTerminal.State?): String =
    session?.displayNumber?.let { stringResource(R.string.terminal_tab_title, it) }
        ?: stringResource(R.string.nav_terminal)
