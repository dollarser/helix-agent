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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.feature.conversation.R as ConversationR
import com.helix.ui.shared.R as SharedR

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
                            if (safLoadFailed) {
                                Text(str(R.string.files_read_directory_error))
                            }
                            if (safLoadFailed || safSources.isEmpty()) {
                                TextButton(
                                    { reloadTick++ },
                                    modifier = Modifier.testTag("files-saf-retry"),
                                ) { Text(str(SharedR.string.chat_retry)) }
                            }
                            if (safSources.isEmpty() && !safLoadFailed) {
                                Text(
                                    str(R.string.files_saf_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.testTag("files-saf-empty"),
                                )
                            }
                            safSources.forEach { source ->
                                FilesSafSourceRow(source) { revokeScope(source) }
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
                            Text(str(ConversationR.string.files_close))
                        }
                    },
                    modifier = Modifier.testTag("files-saf-dialog"),
                )
            }
        }
    }
}

/** Reserve the action width before measuring a provider-supplied directory name. */
@Composable
@Suppress("FunctionName")
internal fun FilesSafSourceRow(
    source: com.helix.feature.files.SafTreeSource,
    onRemove: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().testTag("files-saf-source-${source.scopeId}"),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                source.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                source.scopeId,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onRemove, modifier = Modifier.testTag("files-saf-remove-${source.scopeId}")) {
            Text(stringResource(R.string.files_remove))
        }
    }
}
