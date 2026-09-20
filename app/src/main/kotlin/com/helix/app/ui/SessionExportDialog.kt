package com.helix.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.app.export.SessionExportService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** One user-selected session and a newly created target; never invokes the agent. */
@Composable
@Suppress("FunctionName", "LongMethod")
internal fun SessionExportDialog(
    sessionId: String,
    service: SessionExportService,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var ready by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var copied by remember { mutableLongStateOf(0L) }
    var notice by remember { mutableStateOf<Int?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(service) {
        try {
            val recovery = service.cleanupInterrupted()
            if (recovery.interrupted || recovery.partialMayRemain) notice = R.string.session_export_interrupted
            ready = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            notice = R.string.session_export_failed
        }
    }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) { uri ->
            if (uri != null) {
                running = true
                copied = 0
                notice = null
                job =
                    scope.launch {
                        try {
                            service.export(sessionId, uri) { copied = it }
                            notice = R.string.session_export_complete
                        } catch (cancelled: CancellationException) {
                            notice = R.string.session_export_cancelled
                            throw cancelled
                        } catch (_: Exception) {
                            notice = R.string.session_export_failed
                        } finally {
                            running = false
                        }
                    }
            }
        }
    AlertDialog(
        onDismissRequest = { if (running) job?.cancel() else onDismiss() },
        title = { Text(stringResource(R.string.session_export_title)) },
        text = { SessionExportBody(running, copied, notice) },
        confirmButton = {
            TextButton(
                enabled = ready && !running,
                modifier = Modifier.testTag("session-export-create"),
                onClick = {
                    try {
                        picker.launch("helix-session.jsonl")
                    } catch (_: android.content.ActivityNotFoundException) {
                        notice = R.string.session_export_failed
                    }
                },
            ) { Text(stringResource(R.string.session_export_choose)) }
        },
        dismissButton = {
            TextButton(
                modifier = Modifier.testTag("session-export-dismiss"),
                onClick = { if (running) job?.cancel() else onDismiss() },
            ) {
                Text(stringResource(if (running) R.string.session_export_cancel else R.string.session_export_close))
            }
        },
    )
}

@Composable
@Suppress("FunctionName")
private fun SessionExportBody(
    running: Boolean,
    copied: Long,
    notice: Int?,
) {
    Column(Modifier.testTag("session-export-dialog").verticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.session_export_scope))
        if (running) {
            LinearProgressIndicator()
            Text(stringResource(R.string.session_export_progress, copied))
        }
        notice?.let { Text(stringResource(it), Modifier.testTag("session-export-notice")) }
    }
}
