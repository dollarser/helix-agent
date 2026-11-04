package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.files.FileManagerService
import com.helix.app.files.viewerPageCount

@Composable
@Suppress("FunctionName", "LongMethod")
internal fun FileTextPageControls(state: FilesScreenState) {
    val info = state.viewerIdentity?.takeIf { it.isText } ?: return
    val pages = viewerPageCount(info.sizeBytes, FileManagerService.VIEWER_TEXT_BYTES)
    if (pages <= 1) return
    var jump by remember(state.openFile) { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val busy = state.preview is FilePreviewState.Loading
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(
            { state.showViewerPage(state.viewerPage - 1) },
            enabled = !busy && state.viewerPage > 0,
            modifier = Modifier.testTag("files-text-previous"),
        ) { Text(stringResource(R.string.files_text_previous)) }
        TextButton(
            {
                input = (state.viewerPage + 1).toString()
                jump = true
            },
            enabled = !busy,
            modifier = Modifier.testTag("files-text-page"),
        ) {
            Text(stringResource(R.string.files_text_page, state.viewerPage + 1, pages))
        }
        TextButton(
            { state.showViewerPage(state.viewerPage + 1) },
            enabled = !busy && state.viewerPage < pages - 1,
            modifier = Modifier.testTag("files-text-next"),
        ) { Text(stringResource(R.string.files_text_next)) }
    }
    if (jump) {
        val selected = input.toLongOrNull()?.takeIf { it in 1..pages }
        AlertDialog(
            onDismissRequest = { jump = false },
            title = { Text(stringResource(R.string.files_text_jump)) },
            text = {
                OutlinedTextField(
                    input,
                    { input = it.take(19) },
                    singleLine = true,
                    keyboardOptions =
                        androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                        ),
                    modifier = Modifier.testTag("files-text-page-input"),
                )
            },
            confirmButton = {
                TextButton(
                    {
                        state.showViewerPage(requireNotNull(selected) - 1)
                        jump = false
                    },
                    enabled = selected != null,
                    modifier = Modifier.testTag("files-text-page-confirm"),
                ) { Text(stringResource(R.string.files_confirm)) }
            },
            dismissButton = { TextButton({ jump = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}
