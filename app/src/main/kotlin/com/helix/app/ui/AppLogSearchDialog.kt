package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.diagnostics.AppLogSnapshot
import com.helix.app.diagnostics.DiagnosticReportService
import com.helix.ui.shared.indicatedVerticalScroll
import kotlinx.coroutines.CancellationException

@Composable
@Suppress("FunctionName", "LongMethod", "TooGenericExceptionCaught") // Failed OS reads remain visible failures.
internal fun AppLogSearchDialog(service: DiagnosticReportService, onDismiss: () -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    var snapshot by remember { mutableStateOf<AppLogSnapshot?>(null) }
    var query by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(attempt) {
        snapshot = null
        failed = false
        try {
            snapshot = service.appLogs()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    val matches =
        remember(snapshot, query) {
            snapshot
                ?.text
                ?.lineSequence()
                ?.filter { it.contains(query, ignoreCase = true) }
                ?.take(101)
                ?.toList()
                .orEmpty()
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_log_search)) },
        text = {
            Column(Modifier.indicatedVerticalScroll(rememberScrollState()), Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.app_log_scope))
                OutlinedTextField(
                    query,
                    { query = it.take(256) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.audit_search_label)) },
                )
                if (failed) Text(stringResource(R.string.command_output_failed))
                if (snapshot == null && !failed) Text(stringResource(R.string.command_output_loading))
                if (snapshot != null) {
                    Text(stringResource(R.string.app_log_limit))
                    SelectionContainer { Text(matches.take(100).joinToString("\n").ifEmpty { "—" }) }
                }
            }
        },
        dismissButton = { TextButton({ attempt++ }) { Text(stringResource(R.string.app_log_refresh)) } },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.command_detail_back)) } },
    )
}
