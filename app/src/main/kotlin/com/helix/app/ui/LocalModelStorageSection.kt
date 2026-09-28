package com.helix.app.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.app.localmodel.LocalModelStorageSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
@Suppress("FunctionName", "LongMethod", "TooGenericExceptionCaught", "SwallowedException")
internal fun LocalModelStorageSection(
    downloading: Boolean,
    load: suspend () -> LocalModelStorageSnapshot,
    clear: suspend (LocalModelStorageSnapshot) -> Unit,
) {
    var snapshot by remember { mutableStateOf<LocalModelStorageSnapshot?>(null) }
    var confirmation by remember { mutableStateOf<LocalModelStorageSnapshot?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(downloading, refresh) {
        snapshot = null
        if (!downloading) {
            try {
                snapshot = load()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                failed = true
            }
        }
    }
    Text(stringResource(R.string.local_model_storage_scope))
    snapshot?.let { current ->
        Text(
            stringResource(
                R.string.local_model_storage_usage,
                formatBytes(current.installedBytes),
                formatBytes(current.downloadBytes),
            ),
            Modifier.testTag("local-model-storage-usage"),
        )
        TextButton(
            onClick = { confirmation = current },
            enabled = !downloading && !busy && current.downloadCount > 0,
            modifier = Modifier.testTag("local-model-storage-clear"),
        ) { Text(stringResource(R.string.local_model_storage_clear)) }
    }
    if (failed) Text(stringResource(R.string.local_model_storage_failed), Modifier.testTag("local-model-storage-error"))
    TextButton(
        onClick = {
            failed = false
            refresh++
        },
        enabled = !downloading && !busy,
        modifier = Modifier.testTag("local-model-storage-refresh"),
    ) { Text(stringResource(R.string.local_model_storage_refresh)) }
    confirmation?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(stringResource(R.string.local_model_storage_clear)) },
            text = { Text(stringResource(R.string.local_model_storage_confirm, formatBytes(target.downloadBytes))) },
            confirmButton = {
                TextButton(
                    enabled = !downloading && !busy,
                    modifier = Modifier.testTag("local-model-storage-confirm"),
                    onClick = {
                        confirmation = null
                        busy = true
                        scope.launch {
                            try {
                                clear(target)
                                failed = false
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (_: Exception) {
                                failed = true
                            } finally {
                                busy = false
                                refresh++
                            }
                        }
                    },
                ) { Text(stringResource(R.string.local_model_storage_clear)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmation = null },
                    modifier = Modifier.testTag("local-model-storage-cancel"),
                ) { Text(stringResource(R.string.chat_details_close)) }
            },
        )
    }
}
