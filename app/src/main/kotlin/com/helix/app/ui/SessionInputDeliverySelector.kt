package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Session default, independent of drafts, queued inputs and any particular active Turn. */
@Composable
@Suppress("FunctionName", "TooGenericExceptionCaught")
internal fun SessionInputDeliverySelector(
    immediate: Boolean,
    enabled: Boolean,
    onSelect: suspend (Boolean) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    fun select(value: Boolean) {
        scope.launch {
            busy = true
            failed = false
            try {
                failed = !onSelect(value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true
            } finally {
                busy = false
            }
        }
    }
    Column(modifier = Modifier.testTag("session-input-delivery-selector")) {
        Text(stringResource(R.string.session_message_delivery_title))
        Text(stringResource(R.string.session_message_delivery_hint), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                enabled = enabled && !busy,
                onClick = { select(false) },
                modifier = Modifier.testTag("session-input-delivery-queue"),
            ) { Text(stringResource(R.string.session_input_delivery_queue) + if (!immediate) " ✓" else "") }
            TextButton(
                enabled = enabled && !busy,
                onClick = { select(true) },
                modifier = Modifier.testTag("session-input-delivery-steer"),
            ) { Text(stringResource(R.string.session_input_delivery_steer) + if (immediate) " ✓" else "") }
        }
        if (failed) Text(stringResource(R.string.connector_failed), color = MaterialTheme.colorScheme.error)
    }
}
