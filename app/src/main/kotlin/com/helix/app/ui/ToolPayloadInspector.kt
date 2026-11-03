package com.helix.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.helix.app.R
import com.helix.app.chat.ToolTimelineRow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private val prettyPayload = Json { prettyPrint = true }

internal fun formatToolPayload(value: String): String =
    try {
        prettyPayload.encodeToString(
            JsonElement.serializer(),
            Json.parseToJsonElement(
                com.helix.app.application.diagnostics.DiagnosticRedaction
                    .redact(value),
            ),
        )
    } catch (_: kotlinx.serialization.SerializationException) {
        com.helix.app.application.diagnostics.DiagnosticRedaction
            .redact(value)
    } catch (_: IllegalArgumentException) {
        com.helix.app.application.diagnostics.DiagnosticRedaction
            .redact(value)
    }

/** Reads the original redacted result only on explicit inspection; no tool dispatch or Runtime log access. */
@Composable
@Suppress("FunctionName", "LongMethod") // One read-only sheet keeps its load, disclosure and copy state together.
internal fun ToolPayloadInspector(
    row: ToolTimelineRow,
    readResult: suspend (String, String) -> com.helix.app.chat.ToolInspectionContent?,
) {
    var open by rememberSaveable(row.turnId, row.callId) { mutableStateOf(false) }
    val context = LocalContext.current
    val args = remember(row.requestSummary) { formatToolPayload(row.requestSummary) }
    val result = remember(row.resultSummary) { row.resultSummary?.let(::formatToolPayload) }
    TextButton(onClick = { open = true }, modifier = Modifier.testTag("tool-inspector-${row.callId}")) {
        Text(stringResource(R.string.tool_inspector_open))
    }
    if (open) {
        var complete by remember(
            row.turnId,
            row.callId,
        ) { mutableStateOf<com.helix.app.chat.ToolInspectionContent?>(null) }
        var loaded by remember(row.turnId, row.callId) { mutableStateOf(false) }
        LaunchedEffect(row.turnId, row.callId) {
            complete =
                try {
                    readResult(row.turnId, row.callId)
                } catch (
                    cancelled: kotlinx.coroutines.CancellationException,
                ) {
                    throw cancelled
                } catch (_: java.io.IOException) {
                    null
                } catch (_: IllegalStateException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            loaded = true
        }
        val displayedResult = complete?.result?.let(::formatToolPayload) ?: result
        val displayedArgs = complete?.arguments?.let(::formatToolPayload) ?: args

        ConversationSheet(stringResource(R.string.tool_inspector_open), "tool-inspector", {
            open = false
        }, scrollContent = false) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(
                        if (complete?.result !=
                            null
                        ) {
                            R.string.tool_inspector_full
                        } else if (loaded) {
                            R.string.tool_inspector_unavailable
                        } else {
                            R.string.tool_inspector_loading
                        },
                    ),
                )
                TextButton(enabled = complete != null, onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText(
                            row.toolName,
                            displayedArgs + "\n\n" + displayedResult.orEmpty(),
                        ),
                    )
                }, modifier = Modifier.testTag("tool-inspector-copy")) { Text(stringResource(R.string.files_copy)) }
                SelectionContainer {
                    Text(
                        displayedArgs + "\n\n" + displayedResult.orEmpty(),
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}
