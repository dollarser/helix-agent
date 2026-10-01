package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.app.privacy.ProviderEvidenceCleanup
import com.helix.app.privacy.ProviderEvidenceCleanup.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Explicit one-page user action; opening storage statistics never starts the subscription Runtime. */
@Composable
@Suppress("FunctionName", "TooGenericExceptionCaught", "SwallowedException")
internal fun ProviderEvidenceCleanupSection(clean: suspend (String?) -> ProviderEvidenceCleanup) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ProviderEvidenceCleanup?>(null) }
    Column {
        Text(stringResource(R.string.replay_cleanup_scope))
        OutlinedButton(
            enabled = !busy,
            modifier = Modifier.testTag("replay-cleanup-run"),
            onClick = {
                if (!busy) {
                    busy = true
                    scope.launch {
                        try {
                            result = clean(result?.nextAfter)
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            result = ProviderEvidenceCleanup(Status.OUTCOME_UNKNOWN)
                        } finally {
                            busy = false
                        }
                    }
                }
            },
        ) {
            Text(
                stringResource(
                    if (result?.status ==
                        Status.MORE_AVAILABLE
                    ) {
                        R.string.replay_cleanup_next
                    } else {
                        R.string.replay_cleanup_run
                    },
                ),
            )
        }
        if (busy) Text(stringResource(R.string.storage_usage_loading))
        result?.let { outcome ->
            Text(stringResource(replayCleanupStatus(outcome.status)), Modifier.testTag("replay-cleanup-status"))
            if (outcome.status !in setOf(Status.UNAVAILABLE, Status.OUTCOME_UNKNOWN, Status.NOT_APPLICABLE)) {
                Text(
                    stringResource(
                        R.string.replay_cleanup_counts,
                        outcome.inspected,
                        formatBytes(outcome.inspectedBytes),
                        outcome.deleted,
                        formatBytes(outcome.deletedBytes),
                        outcome.retained,
                        outcome.failed,
                    ),
                    Modifier.testTag("replay-cleanup-counts"),
                )
            }
        }
    }
}

private fun replayCleanupStatus(status: Status): Int =
    when (status) {
        Status.NOT_APPLICABLE -> R.string.replay_cleanup_not_applicable
        Status.COMPLETE -> R.string.replay_cleanup_complete
        Status.MORE_AVAILABLE -> R.string.replay_cleanup_more
        Status.BUSY -> R.string.replay_cleanup_busy
        Status.UNAVAILABLE -> R.string.replay_cleanup_unavailable
        Status.OUTCOME_UNKNOWN -> R.string.replay_cleanup_unknown
        Status.HISTORY_UNVERIFIED -> R.string.replay_cleanup_history
        Status.PARTIAL_FAILURE -> R.string.replay_cleanup_partial
    }
