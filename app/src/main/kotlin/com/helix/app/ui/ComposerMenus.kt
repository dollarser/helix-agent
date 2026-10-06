package com.helix.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.helix.app.R
import com.helix.core.model.ReasoningEffort
import kotlinx.coroutines.launch

@Composable
@Suppress("FunctionName", "LongMethod")
internal fun ComposerReasoningMenu(
    reasoning: ReasoningEffort,
    enabled: Boolean,
    onReasoning: (ReasoningEffort) -> Unit,
    modifier: Modifier = Modifier,
    efforts: List<ReasoningEffort> = ReasoningEffort.FALLBACK,
    onDetect: (suspend () -> List<ReasoningEffort>)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var detected by remember(efforts) { mutableStateOf<List<ReasoningEffort>?>(null) }
    var failed by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val options = detected ?: efforts
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    Box(modifier) {
        TextButton(
            onClick = {
                expanded = true
                if (options.isEmpty() && onDetect != null && !busy) {
                    busy = true
                    failed = false
                    scope.launch {
                        try {
                            detected = onDetect()
                        } catch (cancel: kotlinx.coroutines.CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            failed = true
                        } finally {
                            busy = false
                        }
                    }
                }
            },
            enabled = enabled && !busy && (options.isNotEmpty() || onDetect != null),
            modifier = Modifier.testTag("chat-reasoning-menu"),
        ) {
            Text(
                stringResource(
                    when {
                        busy -> R.string.chat_reasoning_detecting
                        options.isEmpty() -> R.string.chat_reasoning_detect
                        else -> R.string.chat_reasoning_selection
                    },
                    reasoningLabel(reasoning),
                ),
                maxLines = 1,
            )
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            ReasoningChoices(options, reasoning, busy, failed, enabled) {
                expanded = false
                onReasoning(it)
            }
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun ReasoningChoices(
    options: List<ReasoningEffort>,
    reasoning: ReasoningEffort,
    busy: Boolean,
    failed: Boolean,
    enabled: Boolean,
    onSelect: (ReasoningEffort) -> Unit,
) {
    if (options.isEmpty()) {
        DropdownMenuItem(
            text = {
                Text(
                    stringResource(
                        when {
                            busy -> R.string.chat_reasoning_detecting
                            failed -> R.string.chat_reasoning_detection_failed
                            else -> R.string.chat_reasoning_not_confirmed
                        },
                    ),
                )
            },
            onClick = {},
            enabled = false,
            modifier = Modifier.testTag("chat-reasoning-status"),
        )
    }
    options.forEach { value ->
        DropdownMenuItem(
            text = { Text(reasoningLabel(value)) },
            onClick = { onSelect(value) },
            enabled = enabled && !busy,
            modifier =
                Modifier
                    .semantics { selected = reasoning == value }
                    .testTag("chat-reasoning-${value.name.lowercase()}"),
        )
    }
}

/** Labels are presentation only; available values come from the selected model's metadata. */
@Composable
private fun reasoningLabel(effort: ReasoningEffort): String =
    when (effort.name) {
        "OFF" -> stringResource(R.string.chat_reasoning_default)
        "LOW" -> stringResource(R.string.chat_reasoning_low)
        "MEDIUM" -> stringResource(R.string.chat_reasoning_medium)
        "HIGH" -> stringResource(R.string.chat_reasoning_high)
        "XHIGH" -> stringResource(R.string.chat_reasoning_xhigh)
        else -> effort.name.lowercase()
    }
