package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.helix.app.R

@Composable
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod")
internal fun FilesSafDialog(
    state: FilesScreenState,
    actions: FilesScreenActions,
    onPickTree: () -> Unit,
) {
    with(actions) {
        with(state) {
            if (safPanelOpen) {
                AlertDialog(
                    onDismissRequest = { safPanelOpen = false },
                    title = { Text(str(R.string.files_saf_panel_title)) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                str(R.string.files_saf_panel_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (safSources.isEmpty()) {
                                Text(
                                    str(R.string.files_saf_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.testTag("files-saf-empty"),
                                )
                            }
                            safSources.forEach { source ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth().testTag("files-saf-source-${source.scopeId}"),
                                ) {
                                    Column {
                                        Text(source.displayName, style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            source.scopeId,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    TextButton(
                                        onClick = {
                                            revokeScope(source)
                                        },
                                        modifier = Modifier.testTag("files-saf-remove-${source.scopeId}"),
                                    ) {
                                        Text(str(R.string.files_remove))
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = { onPickTree() },
                            modifier = Modifier.testTag("files-saf-add"),
                        ) {
                            Text(str(R.string.files_add_reauthorize))
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { safPanelOpen = false },
                            modifier = Modifier.testTag("files-saf-close"),
                        ) {
                            Text(str(R.string.files_close))
                        }
                    },
                    modifier = Modifier.testTag("files-saf-dialog"),
                )
            }
        }
    }
}
