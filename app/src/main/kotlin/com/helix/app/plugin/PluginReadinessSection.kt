package com.helix.app.plugin

import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.helix.feature.conversation.R as ConversationR

/** Opening the tab reads current facts only; retries never repair, authorize or reconnect. */
@Composable
@Suppress("FunctionName", "TooGenericExceptionCaught") // A failed status read remains an explicit retryable failure.
internal fun PluginReadinessSection(
    record: InstalledPlugin,
    service: PluginService,
    refreshKey: Int = 0,
    readSnapshot: () -> PluginReadinessSnapshot = { service.componentReadiness(record) },
) {
    var state by remember(record, refreshKey) { mutableStateOf<PluginReadinessSnapshot?>(null) }
    var failure by remember(record, refreshKey) { mutableStateOf(false) }
    var retry by remember(record, refreshKey) { mutableIntStateOf(0) }
    LaunchedEffect(record, service, refreshKey, retry) {
        failure = false
        try {
            state = withContext(Dispatchers.IO) { readSnapshot() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            com.helix.app.diagnostics.OperationFailureLog.record(
                com.helix.app.diagnostics.OperationFailureLog.Stage.PLUGIN_READINESS,
                error,
            )
            state = null
            failure = true
        }
    }
    when {
        failure -> {
            Text(stringResource(R.string.plugin_settings_read_failed), color = MaterialTheme.colorScheme.error)
            TextButton({ retry++ }, Modifier.testTag("plugin-readiness-retry-${record.id}")) {
                Text(stringResource(R.string.session_settings_retry))
            }
        }

        state == null -> {
            Text(stringResource(ConversationR.string.composer_permission_loading))
        }

        else -> {
            PluginReadinessDetails(requireNotNull(state), packageHash = record.hash)
        }
    }
}
