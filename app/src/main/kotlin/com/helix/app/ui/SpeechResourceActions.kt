package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
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
import com.helix.app.R

/** Presentation only: owners serialize resource changes and preserve unrelated files. */
@Composable
@Suppress("FunctionName")
internal fun SpeechResourceActions(
    bytes: Long,
    busy: Boolean,
    tag: String,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    if (bytes > 0) {
        Column {
            Text(stringResource(R.string.speech_resource_storage, bytes / 1048576.0))
            TextButton(onDownload, enabled = !busy, modifier = Modifier.testTag("$tag-redownload")) {
                Text(stringResource(R.string.speech_resource_redownload))
            }
            TextButton({ confirming = true }, enabled = !busy, modifier = Modifier.testTag("$tag-remove")) {
                Text(stringResource(R.string.speech_resource_remove))
            }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.speech_resource_remove)) },
            text = { Text(stringResource(R.string.speech_resource_remove_warning)) },
            confirmButton = {
                TextButton({
                    confirming = false
                    onRemove()
                }, enabled = !busy, modifier = Modifier.testTag("$tag-remove-confirm")) {
                    Text(stringResource(R.string.speech_resource_remove))
                }
            },
            dismissButton = { TextButton({ confirming = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
@Suppress("FunctionName")
internal fun SpeechDownloadRecovery(attempts: Int) {
    if (attempts > 0) Text(stringResource(R.string.speech_resource_recovery, attempts))
}
