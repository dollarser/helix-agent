package com.helix.feature.conversation.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.core.model.ToolCallState
import com.helix.feature.conversation.R
import com.helix.feature.conversation.presentation.ToolTimelineRow
import kotlinx.coroutines.delay

/** One-second updates local to a visible running row. No invented progress percentage. */
@Composable
@Suppress("FunctionName")
fun RunningToolClock(row: ToolTimelineRow) {
    val start = row.startedAtElapsedMs ?: return
    if (row.state != ToolCallState.RUNNING) return
    var seconds by remember(row.turnId, row.callId) { mutableLongStateOf(0) }
    LaunchedEffect(row.turnId, row.callId, start) {
        while (true) {
            seconds = (((System.nanoTime() / 1_000_000) - start) / 1000).coerceAtLeast(0)
            delay(1000)
        }
    }
    Text(
        stringResource(R.string.tool_running_elapsed, seconds),
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.testTag("tool-elapsed-${row.callId}"),
    )
}
