package com.helix.app.terminal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.ui.indicatedHorizontalScroll

@Composable
@Suppress("FunctionName")
internal fun TerminalExtraKeys(
    isWriter: Boolean,
    onKey: (TerminalShortcut) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Row {
            ShortcutRow(TerminalShortcuts.common, isWriter, onKey, Modifier.weight(1f))
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("terminal-more-keys")) {
                Text(stringResource(if (expanded) R.string.terminal_fewer_keys else R.string.terminal_more_keys))
            }
        }
        if (expanded) {
            ShortcutRow(TerminalShortcuts.editing, isWriter, onKey)
            ShortcutRow(TerminalShortcuts.symbols, isWriter, onKey)
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun ShortcutRow(
    keys: List<TerminalShortcut>,
    enabled: Boolean,
    onKey: (TerminalShortcut) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.indicatedHorizontalScroll(rememberScrollState())) {
        keys.forEach { shortcut ->
            val description =
                when (shortcut.id) {
                    "up" -> stringResource(R.string.terminal_key_up)
                    "down" -> stringResource(R.string.terminal_key_down)
                    "left" -> stringResource(R.string.terminal_key_left)
                    "right" -> stringResource(R.string.terminal_key_right)
                    else -> shortcut.label
                }
            TextButton(
                onClick = { if (enabled) onKey(shortcut) },
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier =
                    Modifier
                        .testTag(
                            "terminal-key-${shortcut.id}",
                        ).semantics { contentDescription = description },
            ) { Text(shortcut.label) }
        }
    }
}
