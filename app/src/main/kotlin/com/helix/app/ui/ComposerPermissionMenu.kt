package com.helix.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.core.model.SessionPermissionMode

/** A direct current-session picker. It neither changes app defaults nor grants a per-call approval. */
@Composable
@Suppress("FunctionName", "LongMethod")
internal fun ComposerPermissionMenu(
    mode: SessionPermissionMode?,
    pending: Boolean,
    onSelect: (SessionPermissionMode) -> Unit,
    onSettings: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { expanded = true },
            enabled = mode != null && !pending,
            modifier = Modifier.testTag("chat-permission-menu"),
        ) {
            val label =
                when {
                    pending -> R.string.composer_permission_saving
                    mode == null -> R.string.composer_permission_loading
                    else -> mode.labelRes()
                }
            Text(
                "${stringResource(label)} ▾",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded && !pending, { expanded = false }, Modifier.widthIn(max = 300.dp)) {
            Text(
                stringResource(R.string.composer_permission_title),
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium,
            )
            PRESETS.forEach { option ->
                DropdownMenuItem(
                    text = { Text((if (option == mode) "✓ " else "") + stringResource(option.labelRes())) },
                    onClick = {
                        expanded = false
                        if (mode != option) onSelect(option)
                    },
                    modifier =
                        Modifier
                            .testTag(
                                "chat-permission-${option.name}",
                            ).semantics { selected = option == mode },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.composer_permission_custom)) },
                onClick = {
                    expanded = false
                    onSettings()
                },
                modifier = Modifier.testTag("chat-permission-custom"),
            )
            Text(
                stringResource(R.string.composer_permission_scope),
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
