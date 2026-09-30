package com.helix.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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

private fun matchesModelQuery(
    row: ProviderRowUi,
    model: String,
    query: String,
): Boolean = listOf(model, row.modelLabel(model), row.displayName).any { it.contains(query, ignoreCase = true) }

@Composable
@Suppress("FunctionName")
private fun ConversationModelItem(
    row: ProviderRowUi,
    model: String,
    current: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Column {
                Text((if (current) "✓ " else "") + row.modelLabel(model))
                Text(row.displayName, style = MaterialTheme.typography.labelSmall)
                if (!row.chatSelectable) Text(stringResource(R.string.provider_models_connection_required))
                row.modelGenerations[model]?.failure?.let {
                    Text(
                        stringResource(
                            com.helix.app.provider.ConnectionTestMapping
                                .codeLabel(it),
                        ),
                    )
                }
            }
        },
        onClick = { if (enabled && !current && row.modelSelectable(model)) onSelect() },
        enabled = enabled && row.modelSelectable(model),
        modifier = Modifier.semantics { selected = current }.testTag("chat-model-${row.id}-$model"),
    )
}

@Composable
@Suppress("FunctionName", "LongParameterList", "LongMethod") // Searchable model picker; no new execution entry.
internal fun ComposerModelMenu(
    providers: List<ProviderRowUi>,
    providerId: String?,
    model: String?,
    enabled: Boolean,
    onSelect: (String, String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val currentProvider = providers.firstOrNull { it.id == providerId }
    val currentLabel = model?.let { currentProvider?.modelLabel(it) ?: it }
    val entries =
        providers
            .flatMap { row -> row.conversationModels.map { row to it } }
            .filter { (row, candidate) -> matchesModelQuery(row, candidate, query) }
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    Box {
        TextButton({ expanded = true }, enabled = enabled, modifier = Modifier.testTag("chat-model-menu")) {
            Text(
                "${currentLabel ?: stringResource(R.string.chat_select_model)} ▾",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (expanded) {
            AlertDialog(
                onDismissRequest = { expanded = false },
                title = { Text(stringResource(R.string.chat_select_model)) },
                text = {
                    Column {
                        OutlinedTextField(
                            query,
                            { query = it },
                            singleLine = true,
                            label = { Text(stringResource(R.string.provider_models_search)) },
                            modifier = Modifier.testTag("chat-model-search"),
                        )
                        if (model != null && model !in currentProvider?.conversationModels.orEmpty()) {
                            Text(
                                stringResource(R.string.provider_models_current_hidden, currentLabel.orEmpty()),
                                modifier = Modifier.testTag("chat-model-hidden-current"),
                            )
                        }
                        LazyColumn(Modifier.heightIn(max = 360.dp)) {
                            if (entries.isEmpty()) {
                                item {
                                    Text(
                                        stringResource(R.string.provider_models_picker_empty),
                                        modifier = Modifier.testTag("chat-model-empty"),
                                    )
                                }
                            }
                            items(entries, key = { (row, candidate) ->
                                "${row.id.length}:${row.id}$candidate"
                            }) { (row, candidate) ->
                                val current = row.id == providerId && candidate == model
                                ConversationModelItem(row, candidate, current, enabled) {
                                    expanded = false
                                    onSelect(row.id, candidate)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { expanded = false },
                    ) { Text(stringResource(R.string.chat_details_close)) }
                },
            )
        }
    }
}
