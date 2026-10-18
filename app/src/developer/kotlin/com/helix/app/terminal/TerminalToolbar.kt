package com.helix.app.terminal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.helix.app.R

@Composable
@Suppress("FunctionName", "LongParameterList")
internal fun TerminalTopBar(
    state: TerminalPageState,
    model: ManualTerminalViewModel,
    onBack: () -> Unit,
    onHelp: () -> Unit,
    keyboardVisible: Boolean,
    onToggleKeyboard: () -> Unit,
    onRuntimeSettings: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.files_back)) }
        Text(stringResource(R.string.nav_terminal), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        TextButton(
            onClick = onToggleKeyboard,
            enabled = state.connection != null && state.isWriter,
            modifier = Modifier.testTag("terminal-keyboard"),
        ) {
            val label = if (keyboardVisible) R.string.terminal_hide_keyboard else R.string.terminal_show_keyboard
            Text(stringResource(label))
        }
        Box {
            IconButton({ menu = true }, Modifier.testTag("terminal-menu")) {
                Icon(painterResource(R.drawable.ic_chat_more), stringResource(R.string.terminal_menu))
            }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                val items =
                    listOf(
                        Triple(R.string.terminal_refresh, "terminal-refresh") { model.refresh() },
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
                        enabled = !state.busy,
                        modifier = Modifier.testTag(tag),
                    )
                }
            }
        }
    }
}
