@file:Suppress("FunctionName")

package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.diagnostics.DiagnosticArchiveStatus
import com.helix.app.diagnostics.DiagnosticKind
import com.helix.app.diagnostics.DiagnosticReportService
import com.helix.app.diagnostics.DiagnosticRetention
import com.helix.feature.conversation.presentation.formatToolPayload
import kotlinx.coroutines.CancellationException
import com.helix.feature.conversation.R as ConversationR

@Composable
internal fun DiagnosticStorageSection(service: DiagnosticReportService) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton({ open = true }, Modifier.testTag("diagnostic-storage-open")) {
        Text(stringResource(R.string.diagnostic_storage_title))
    }
    if (open) DiagnosticStorageDialog(service) { open = false }
}

@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod") // One stateful settings form, all storage work delegated off main.
private fun DiagnosticStorageDialog(service: DiagnosticReportService, dismiss: () -> Unit) {
    var status by remember { mutableStateOf<DiagnosticArchiveStatus?>(null) }
    var error by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var normal by remember { mutableStateOf("14") }
    var failure by remember { mutableStateOf("90") }
    var budget by remember { mutableStateOf("256") }
    var request by remember { mutableStateOf<suspend () -> Unit>({}) }
    var generation by remember { mutableStateOf(0) }
    var detail by remember { mutableStateOf<String?>(null) }
    var showRecent by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(generation) {
        busy = true
        error = false
        try {
            request()
            status = service.retentionStatus()
            status?.policy?.let {
                normal = it.normalDays.toString()
                failure = it.failureDays.toString()
                budget = it.budgetMiB.toString()
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            error = true
        } finally {
            busy = false
        }
    }
    val normalDays = normal.toIntOrNull()
    val failureDays = failure.toIntOrNull()
    val budgetMiB = budget.toIntOrNull()
    val valid =
        normalDays != null && failureDays != null && budgetMiB != null &&
            normalDays in 1..365 && failureDays in normalDays..365 && budgetMiB in 64..2048
    AlertDialog(
        onDismissRequest = { if (!busy) dismiss() },
        title = { Text(stringResource(R.string.diagnostic_storage_title)) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (error) {
                    Text(
                        stringResource(R.string.diagnostic_storage_error),
                        Modifier.testTag("diagnostic-storage-error"),
                    )
                }
                status?.let { current ->
                    Text(
                        stringResource(
                            R.string.diagnostic_storage_usage,
                            current.bytes / 1024,
                            current.policy.budgetMiB,
                        ),
                    )
                    current.byKind.forEach { (kind, bytes) ->
                        Text("${stringResource(kindLabel(kind))}: ${bytes / 1024} KiB")
                    }
                    if (current.protectedBytes > 0) {
                        Text(stringResource(R.string.diagnostic_storage_protected, current.protectedBytes / 1024))
                    }
                    if (current.dropped > 0) {
                        Text(stringResource(R.string.diagnostic_storage_limited, current.dropped))
                    }
                    if (current.unreadableFiles >
                        0
                    ) {
                        Text(stringResource(R.string.diagnostic_storage_unreadable, current.unreadableFiles))
                    }
                    if (service.diagnosticStorageFailure()) Text(stringResource(R.string.diagnostic_storage_error))
                    if (current.bytes >=
                        current.policy.budgetBytes
                    ) {
                        Text(stringResource(R.string.diagnostic_storage_full))
                    }
                }
                RetentionField(
                    stringResource(R.string.diagnostic_storage_normal),
                    normal,
                    "diagnostic-days-normal",
                    !busy,
                ) {
                    normal =
                        it
                }
                RetentionField(
                    stringResource(R.string.diagnostic_storage_failure),
                    failure,
                    "diagnostic-days-failure",
                    !busy,
                ) {
                    failure =
                        it
                }
                RetentionField(stringResource(R.string.diagnostic_storage_budget), budget, "diagnostic-budget", !busy) {
                    budget =
                        it
                }
                if (!valid) Text(stringResource(R.string.diagnostic_storage_invalid))
                SettingsActions {
                    TextButton({
                        request =
                            { service.setRetention(DiagnosticRetention(normalDays!!, failureDays!!, budgetMiB!!)) }
                        generation++
                    }, enabled = valid && !busy, modifier = Modifier.testTag("diagnostic-storage-save")) {
                        Text(stringResource(R.string.diagnostic_storage_save))
                    }
                    TextButton(
                        {
                            request = { service.cleanDiagnostics() }
                            generation++
                        },
                        enabled = !busy,
                        modifier = Modifier.testTag("diagnostic-storage-clean"),
                    ) { Text(stringResource(R.string.diagnostic_storage_clean)) }
                }
                TextButton({ showRecent = !showRecent }) { Text(stringResource(R.string.diagnostic_storage_recent)) }
                if (showRecent) {
                    status?.rows?.forEach { row ->
                        HorizontalDivider()
                        Text(row.event)
                        Text(
                            java.text.DateFormat
                                .getDateTimeInstance()
                                .format(java.util.Date(row.createdAt)),
                        )
                        if (row.cleared) Text(stringResource(R.string.diagnostic_storage_cleared))
                        SettingsActions {
                            TextButton({
                                request = { service.pinDiagnostic(row.id, !row.pinned) }
                                generation++
                            }, enabled = !busy) {
                                val label =
                                    if (row.pinned) {
                                        R.string.diagnostic_storage_unpin
                                    } else {
                                        R.string.diagnostic_storage_pin
                                    }
                                Text(stringResource(label))
                            }
                            if (!row.cleared) {
                                TextButton({
                                    request = { detail = service.diagnosticDetail(row.id) }
                                    generation++
                                }, enabled = !busy) { Text(stringResource(R.string.diagnostic_storage_detail)) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                dismiss,
                enabled = !busy,
            ) { Text(stringResource(ConversationR.string.files_close)) }
        },
    )
    detail?.let { body ->
        val formatted = remember(body) { formatToolPayload(body) }
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(stringResource(R.string.diagnostic_storage_detail)) },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    if (formatted.length > 65_536) Text(stringResource(R.string.diagnostic_storage_preview_limit))
                    Text(formatted.take(65_536))
                }
            },
            dismissButton = {
                TextButton({ clipboard.setText(AnnotatedString(formatted)) }) {
                    Text(stringResource(R.string.diagnostics_preview_copy))
                }
            },
            confirmButton = {
                TextButton(
                    { detail = null },
                ) { Text(stringResource(ConversationR.string.files_close)) }
            },
        )
    }
}

@Composable
private fun RetentionField(
    label: String,
    value: String,
    tag: String,
    enabled: Boolean,
    change: (String) -> Unit,
) {
    OutlinedTextField(
        value,
        { change(it.filter(Char::isDigit).take(4)) },
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

private fun kindLabel(kind: DiagnosticKind): Int =
    when (kind) {
        DiagnosticKind.NORMAL -> R.string.diagnostic_kind_normal
        DiagnosticKind.FAILURE -> R.string.diagnostic_kind_failure
        DiagnosticKind.LOG -> R.string.diagnostic_kind_log
        DiagnosticKind.ATTACHMENT -> R.string.diagnostic_kind_attachment
        DiagnosticKind.FAILURE_ATTACHMENT -> R.string.diagnostic_kind_failure_attachment
        DiagnosticKind.TEMPORARY -> R.string.diagnostic_kind_temporary
    }
