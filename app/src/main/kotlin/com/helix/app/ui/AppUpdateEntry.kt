@file:Suppress("FunctionName")

package com.helix.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.helix.app.R
import com.helix.app.update.AppUpdateService
import com.helix.app.update.UpdateCheck
import com.helix.app.update.UpdateCheckException
import com.helix.app.update.UpdateFailure
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

@Composable
internal fun AppUpdateEntry() {
    val context = LocalContext.current
    val info = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0) }
    val service =
        remember(info) {
            AppUpdateService(info.versionName.orEmpty(), context.packageName, Build.SUPPORTED_ABIS.toList())
        }
    UpdateCheckEntry(
        installedCode = info.longVersionCode,
        check = service::check,
        openDownload = { url ->
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                true
            } catch (_: ActivityNotFoundException) {
                false
            } catch (_: SecurityException) {
                false
            }
        },
    )
}

private data class UpdateDialogState(
    val result: UpdateCheck? = null,
    val failure: UpdateFailure? = null,
    val openFailed: Boolean = false,
)

/** UI owns this bounded request, not a background download or a durable installation. */
@Composable
internal fun UpdateCheckEntry(
    installedCode: Long,
    check: suspend () -> UpdateCheck,
    openDownload: (String) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateDialogState?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun start() {
        job?.cancel()
        state = UpdateDialogState()
        job =
            scope.launch {
                val checked =
                    try {
                        UpdateDialogState(result = check())
                    } catch (failure: UpdateCheckException) {
                        UpdateDialogState(failure = failure.reason)
                    }
                currentCoroutineContext().ensureActive()
                state = checked
            }
    }
    TextButton(onClick = ::start, modifier = Modifier.testTag("about-check-update")) {
        Text(stringResource(R.string.update_check))
    }
    state?.let { current ->
        UpdateCheckDialog(
            current,
            installedCode,
            retry = ::start,
            download = { url -> state = current.copy(openFailed = !openDownload(url)) },
            close = {
                job?.cancel()
                state = null
            },
        )
    }
}

@Composable
private fun UpdateCheckDialog(
    state: UpdateDialogState,
    installedCode: Long,
    retry: () -> Unit,
    download: (String) -> Unit,
    close: () -> Unit,
) {
    val release = (state.result as? UpdateCheck.Available)?.release
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.update_check)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("update-result")) {
                Text(stringResource(R.string.update_source))
                if (release != null) {
                    Text(stringResource(R.string.update_available, release.versionName))
                    Text(
                        stringResource(
                            R.string.update_variant,
                            if (release.packageName.endsWith(".developer")) "Advanced" else "Standard",
                            release.abis.joinToString(" / "),
                            Formatter.formatShortFileSize(LocalContext.current, release.sizeBytes),
                        ),
                    )
                    if (release.versionCode <= installedCode) Text(stringResource(R.string.update_preview_code))
                    Text(stringResource(R.string.update_browser_handoff))
                } else {
                    Text(stringResource(statusMessage(state)))
                }
                if (state.openFailed) {
                    Text(stringResource(R.string.update_open_failed), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            if (release != null) {
                TextButton(onClick = { download(release.apkUrl) }, modifier = Modifier.testTag("update-download")) {
                    Text(stringResource(R.string.update_download))
                }
            } else if (state.failure != null) {
                TextButton(onClick = retry, modifier = Modifier.testTag("update-retry")) {
                    Text(stringResource(R.string.update_retry))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = close, modifier = Modifier.testTag("update-close")) {
                Text(stringResource(R.string.update_close))
            }
        },
    )
}

private fun statusMessage(state: UpdateDialogState): Int =
    when {
        state.failure != null -> {
            when (state.failure) {
                UpdateFailure.NETWORK -> R.string.update_network_failed
                UpdateFailure.NOT_PUBLISHED -> R.string.update_not_published
                UpdateFailure.INVALID_MANIFEST -> R.string.update_invalid_manifest
                UpdateFailure.UNSUPPORTED_VERSION -> R.string.update_unsupported_version
            }
        }

        state.result == UpdateCheck.Current -> {
            R.string.update_current
        }

        state.result == UpdateCheck.NoCompatibleRelease -> {
            R.string.update_no_compatible
        }

        else -> {
            R.string.update_checking
        }
    }
