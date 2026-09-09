package com.helix.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
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
import com.helix.app.provider.ProviderRowUi

@Composable
@Suppress("FunctionName", "LongParameterList")
internal fun ComposerModelMenu(
    providers: List<ProviderRowUi>,
    providerId: String?,
    model: String?,
    enabled: Boolean,
    onSelect: (String, String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    Box {
        TextButton({ expanded = true }, enabled = enabled, modifier = Modifier.testTag("chat-model-menu")) {
            Text(
                "${model ?: stringResource(R.string.chat_select_model)} ▾",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            if (providers.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_no_provider_available)) },
                    onClick = {},
                    enabled = false,
                )
            }
            providers.filter { it.chatSelectable }.forEach { row ->
                val models = (listOf(row.model) + row.backendModels.orEmpty()).filter { it.isNotBlank() }.distinct()
                models.forEach { candidate ->
                    val current = row.id == providerId && candidate == model
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(if (current) "✓ $candidate" else candidate)
                                Text(row.displayName, style = MaterialTheme.typography.labelSmall)
                            }
                        },
                        onClick = {
                            expanded = false
                            if (enabled && !current) onSelect(row.id, candidate)
                        },
                        modifier = Modifier.semantics { selected = current }.testTag("chat-model-${row.id}-$candidate"),
                    )
                }
            }
        }
    }
}
