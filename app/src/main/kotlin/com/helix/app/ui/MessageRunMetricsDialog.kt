package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.chat.ChatService
import com.helix.app.chat.MessageRunMetrics
import com.helix.app.chat.ModelRequestMetric
import kotlinx.coroutines.CancellationException
import java.util.Locale

internal val LocalRunMetricsHandler = staticCompositionLocalOf<((String) -> Unit)?> { null }

@Composable
@Suppress("FunctionName", "TooGenericExceptionCaught")
internal fun MessageRunMetricsDialog(
    messageId: String,
    chat: ChatService,
    onDismiss: () -> Unit,
) {
    var metrics by remember(messageId) { mutableStateOf<MessageRunMetrics?>(null) }
    var failed by remember(messageId) { mutableStateOf(false) }
    LaunchedEffect(messageId) {
        try {
            metrics = chat.messageRunMetrics(messageId)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            failed = true
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.run_metrics_title)) },
        text = {
            val snapshot = metrics
            if (snapshot == null) {
                Text(stringResource(if (failed) R.string.run_metrics_failed else R.string.citation_loading))
            } else {
                LazyColumn(
                    Modifier.heightIn(max = 480.dp).testTag("run-metrics"),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Text(
                            stringResource(R.string.run_metrics_counts, snapshot.requests.size, snapshot.toolCalls),
                        )
                    }
                    item { Text(stringResource(R.string.run_metrics_hint), style = MaterialTheme.typography.bodySmall) }
                    itemsIndexed(snapshot.requests) { index, request ->
                        ModelRequestMetricRow(index + 1, request)
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
@Suppress("FunctionName")
private fun ModelRequestMetricRow(
    number: Int,
    request: ModelRequestMetric,
) {
    val unknown = stringResource(R.string.run_metrics_unknown)

    @Composable fun duration(value: Long?): String =
        value?.let {
            stringResource(R.string.run_metrics_seconds, it / 1000.0)
        } ?: unknown
    Column {
        Text(stringResource(R.string.run_metrics_request, number), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(
                when (request.state) {
                    "COMPLETED" -> R.string.tool_state_completed
                    "RUNNING" -> R.string.tool_state_running
                    "CANCELLED", "INTERRUPTED" -> R.string.tool_state_cancelled
                    "FAILED" -> R.string.tool_state_failed
                    else -> R.string.tool_state_unknown
                },
            ),
        )
        if (request.observation?.summary == true) Text(stringResource(R.string.run_metrics_summary))
        Text(stringResource(R.string.run_metrics_duration, duration(request.observation?.durationMs)))
        Text(stringResource(R.string.run_metrics_first_text, duration(request.observation?.firstTextMs)))
        Text(stringResource(R.string.run_metrics_first_response, duration(request.observation?.firstResponseMs)))
        Text(
            stringResource(
                R.string.run_metrics_tokens,
                request.inputTokens?.toString() ?: unknown,
                request.outputTokens?.toString() ?: unknown,
            ),
        )
        Text(
            stringResource(
                R.string.run_metrics_speed,
                request.requestTokensPerSecond?.let { String.format(Locale.getDefault(), "%.2f", it) } ?: unknown,
            ),
        )
    }
}
