package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import com.helix.app.R
import kotlinx.coroutines.CancellationException

@Composable
@Suppress("FunctionName")
internal fun DiagnosticReportSection(loadPreview: suspend () -> String) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.testTag("diagnostics-open")) {
        Text(stringResource(R.string.diagnostics_preview_title))
    }
    if (open) DiagnosticReportDialog(loadPreview) { open = false }
}

@Composable
@Suppress("FunctionName", "LongMethod") // One explicit preview/retry/copy dialog state machine.
private fun DiagnosticReportDialog(
    loadPreview: suspend () -> String,
    onDismiss: () -> Unit,
) {
    var attempt by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(attempt) {
        failed = false
        preview = null
        try {
            preview = loadPreview()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.diagnostics_preview_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.diagnostics_preview_scope))
                when {
                    failed -> Text(stringResource(R.string.diagnostics_preview_failed))
                    preview != null -> Text(preview!!, Modifier.testTag("diagnostics-preview"))
                    else -> Text(stringResource(R.string.diagnostics_preview_loading))
                }
                if (copied) Text(stringResource(R.string.code_copied), Modifier.testTag("diagnostics-copied"))
            }
        },
        confirmButton = {
            if (failed) {
                TextButton(onClick = { attempt++ }, modifier = Modifier.testTag("diagnostics-retry")) {
                    Text(stringResource(R.string.chat_retry))
                }
            } else {
                TextButton(
                    enabled = preview != null,
                    modifier = Modifier.testTag("diagnostics-copy"),
                    onClick = {
                        preview?.let {
                            clipboard.setText(AnnotatedString(it))
                            copied = true
                        }
                    },
                ) { Text(stringResource(R.string.diagnostics_preview_copy)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("diagnostics-close")) {
                Text(stringResource(R.string.files_close))
            }
        },
    )
}
