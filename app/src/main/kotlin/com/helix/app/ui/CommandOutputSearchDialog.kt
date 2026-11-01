package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.audit.AuditLogService
import com.helix.app.audit.CommandOutputCursor
import com.helix.app.audit.CommandOutputPage
import kotlinx.coroutines.CancellationException

private data class OutputRequest(
    val query: String,
    val cursor: CommandOutputCursor?,
    val id: String =
        java.util.UUID
            .randomUUID()
            .toString(),
)

@Composable
@Suppress("FunctionName", "LongMethod", "TooGenericExceptionCaught") // Explicit error state; dismissal cancels read.
internal fun CommandOutputSearchDialog(
    service: AuditLogService,
    onOpen: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var request by remember { mutableStateOf<OutputRequest?>(null) }
    var result by remember { mutableStateOf<CommandOutputPage?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(request) {
        val current = request ?: return@LaunchedEffect
        loading = true
        failed = false
        result = null
        try {
            result = service.searchCommandOutput(current.query, current.cursor)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.command_output_search)) },
        text = {
            Column(Modifier.indicatedVerticalScroll(rememberScrollState()), Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.command_output_search_scope))
                OutlinedTextField(
                    query,
                    {
                        query = it.take(256)
                        request = null
                        result = null
                        failed = false
                    },
                    singleLine = true,
                    label = { Text(stringResource(R.string.audit_search_label)) },
                    modifier = Modifier.fillMaxWidth().testTag("command-output-query"),
                )
                TextButton(
                    { request = OutputRequest(query.trim(), null) },
                    enabled = query.isNotBlank() && !loading,
                ) { Text(stringResource(R.string.command_output_search)) }
                if (loading) Text(stringResource(R.string.command_output_loading))
                if (failed) Text(stringResource(R.string.command_output_failed))
                result?.let { page ->
                    Text(stringResource(R.string.command_output_scanned, page.scanned, page.matches.size))
                    if (page.limited) Text(stringResource(R.string.command_output_limited))
                    page.matches.forEach { match ->
                        TextButton({
                            onDismiss()
                            onOpen(match.turnId, match.callId)
                        }) { Text(match.excerpt) }
                    }
                    page.next?.let { next ->
                        TextButton({ request = OutputRequest(requireNotNull(request).query, next) }) {
                            Text(stringResource(R.string.command_output_more))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.command_detail_back)) } },
    )
}
