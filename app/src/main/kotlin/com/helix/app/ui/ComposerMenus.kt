package com.helix.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.core.model.AgentMode
import com.helix.core.model.ReasoningEffort

@Composable
@Suppress("FunctionName")
internal fun ComposerModeMenu(
    mode: AgentMode,
    enabled: Boolean,
    onMode: (AgentMode) -> Unit,
) {
    ComposerMenu(
        label = mode.name.lowercase().replaceFirstChar(Char::uppercase),
        selected = mode,
        options = AgentMode.entries.map { it to it.name.lowercase().replaceFirstChar(Char::uppercase) },
        enabled = enabled,
        tag = "chat-mode",
        onSelect = onMode,
    )
}

@Composable
@Suppress("FunctionName")
internal fun ComposerReasoningMenu(
    reasoning: ReasoningEffort,
    enabled: Boolean,
    onReasoning: (ReasoningEffort) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options =
        listOf(
            ReasoningEffort.OFF to stringResource(R.string.chat_reasoning_default),
            ReasoningEffort.LOW to stringResource(R.string.chat_reasoning_low),
            ReasoningEffort.MEDIUM to stringResource(R.string.chat_reasoning_medium),
            ReasoningEffort.HIGH to stringResource(R.string.chat_reasoning_high),
        )
    ComposerMenu(
        label = stringResource(R.string.chat_reasoning_selection, options.first { it.first == reasoning }.second),
        selected = reasoning,
        options = options,
        enabled = enabled,
        tag = "chat-reasoning",
        onSelect = onReasoning,
        modifier = modifier,
    )
}

@Composable
@Suppress("FunctionName", "LongParameterList")
private fun <T : Enum<T>> ComposerMenu(
    label: String,
    selected: T,
    options: List<Pair<T, String>>,
    enabled: Boolean,
    tag: String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    Box(modifier) {
        TextButton({ expanded = true }, enabled = enabled, modifier = Modifier.testTag("$tag-menu")) {
            Text("$label ▾", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(if (selected == value) "✓ $text" else text) },
                    onClick = {
                        expanded = false
                        if (enabled && selected != value) onSelect(value)
                    },
                    enabled = enabled,
                    modifier =
                        Modifier
                            .heightIn(min = 48.dp)
                            .semantics { this.selected = selected == value }
                            .testTag("$tag-${value.name.lowercase()}"),
                )
            }
        }
    }
}
