package com.helix.feature.conversation.presentation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.helix.feature.conversation.R
import com.helix.ui.shared.SharedSheet

/** One readable preview and, only when needed, one full-content surface. Reads never dispatch work. */
@Composable
@Suppress("FunctionName", "LongMethod")
fun ToolPayloadDetails(
    row: ToolTimelineRow,
    readResult: suspend (String, String) -> ToolInspectionContent?,
) {
    var open by rememberSaveable(row.turnId, row.callId) { mutableStateOf(false) }
    var attempt by remember(row.turnId, row.callId) { mutableIntStateOf(0) }
    var complete by remember(row.turnId, row.callId, row.resultSummary) { mutableStateOf<ToolInspectionContent?>(null) }
    var loaded by remember(row.turnId, row.callId, row.resultSummary) { mutableStateOf(false) }
    LaunchedEffect(row.turnId, row.callId, row.resultSummary, row.state, attempt) {
        loaded = false
        complete = readToolContent(row, readResult)
        loaded = true
    }
    val label = toolPayloadLabels()
    val rawArguments = complete?.arguments ?: row.requestSummary
    val rawResult = complete?.result ?: row.resultSummary
    val args = remember(row.toolName, rawArguments, label) { presentToolPayload(row.toolName, rawArguments, label) }
    val result =
        remember(row.toolName, rawResult, label) { rawResult?.let { presentToolPayload(row.toolName, it, label) } }
    var argsOverflow by remember(args.text) { mutableStateOf(false) }
    var resultOverflow by remember(result?.text) { mutableStateOf(false) }
    ToolPayloadPreview(args.text, R.string.chat_tool_request, "tool-row-args-${row.callId}") { argsOverflow = it }
    ToolPayloadPreview(result?.text.orEmpty(), R.string.chat_tool_result, "tool-row-result-${row.callId}") {
        resultOverflow = it
    }
    val summaryOnly = complete?.result == null && row.resultSummary?.endsWith("…") == true
    val incomplete = args.incomplete || result?.incomplete == true || summaryOnly
    if (incomplete) {
        Text(
            stringResource(if (loaded) R.string.tool_content_unavailable else R.string.tool_content_loading),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("tool-content-load-${row.callId}"),
        )
        if (loaded) {
            TextButton(onClick = { attempt++ }, modifier = Modifier.testTag("tool-content-retry-${row.callId}")) {
                Text(stringResource(R.string.tool_content_retry))
            }
        }
    }
    if (!incomplete && (argsOverflow || resultOverflow)) {
        TextButton(onClick = { open = true }, modifier = Modifier.testTag("tool-content-full-${row.callId}")) {
            Text(stringResource(R.string.tool_content_full))
        }
    }
    if (open) {
        ToolFullContent(row.toolName, args.text, result?.text.orEmpty()) { open = false }
    }
}

@Composable
@Suppress("FunctionName")
private fun ToolPayloadPreview(
    text: String,
    label: Int,
    tag: String,
    onOverflow: (Boolean) -> Unit,
) {
    if (text.isEmpty()) return
    SelectionContainer {
        Text(
            stringResource(label, text.take(PREVIEW_CHARACTERS)),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { onOverflow(text.length > PREVIEW_CHARACTERS || it.hasVisualOverflow) },
            modifier = Modifier.testTag(tag),
        )
    }
}

@Composable
@Suppress("FunctionName")
private fun ToolFullContent(
    name: String,
    args: String,
    result: String,
    close: () -> Unit,
) {
    val context = LocalContext.current
    val content =
        listOfNotNull(
            args.takeIf(String::isNotEmpty)?.let { stringResource(R.string.chat_tool_request, it) },
            result.takeIf(String::isNotEmpty)?.let { stringResource(R.string.chat_tool_result, it) },
        ).joinToString("\n\n")
    SharedSheet(stringResource(R.string.tool_content_full), "tool-content", close, scrollContent = false) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(name, content))
            }, modifier = Modifier.testTag("tool-content-copy")) { Text(stringResource(R.string.files_copy)) }
            SelectionContainer { Text(content, modifier = Modifier.testTag("tool-content-text")) }
        }
    }
}

private suspend fun readToolContent(
    row: ToolTimelineRow,
    readResult: suspend (String, String) -> ToolInspectionContent?,
): ToolInspectionContent? =
    try {
        readResult(row.turnId, row.callId)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: java.io.IOException) {
        null
    } catch (_: IllegalStateException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

private const val PREVIEW_CHARACTERS = 1600
