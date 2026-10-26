package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.helix.app.R
import com.helix.app.voice.ReadAloudPhase
import com.helix.app.voice.ReadAloudService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
// Resource errors stay visible; cancellation propagates.
@Suppress("FunctionName", "LongMethod", "TooGenericExceptionCaught")
internal fun OfflineTtsModelPanel(service: ReadAloudService) {
    val state by service.state.collectAsState()
    val downloaded by service.model.downloadedBytes.collectAsState()
    val scope = rememberCoroutineScope()
    var installed by remember(service) { mutableLongStateOf(0L) }
    var ready by remember(service) { mutableStateOf(false) }
    var checking by remember(service) { mutableStateOf(true) }
    var failed by remember(service) { mutableStateOf(false) }
    var removing by remember(service) { mutableStateOf(false) }
    val downloading = state.phase == ReadAloudPhase.DOWNLOADING
    val busy = checking || removing || state.busy || downloading
    LaunchedEffect(service, state.phase) {
        checking = true
        try {
            ready = service.model.ready() != null
            installed = service.model.installedBytes()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        } finally {
            checking = false
        }
    }
    DisposableEffect(service) { onDispose { cancelTtsDownload(service) } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { cancelTtsDownload(service) }
    Column(Modifier.padding(12.dp).testTag("offline-tts-model")) {
        Text(stringResource(R.string.speech_tts_resource_title))
        Text(stringResource(R.string.speech_tts_resource_description, (service.model.totalBytes + 1048575) / 1048576))
        Text(stringResource(ttsModelStatus(checking, ready)))
        if (failed || state.phase == ReadAloudPhase.FAILED) Text(stringResource(R.string.speech_resource_failed))
        if (downloading) {
            LinearProgressIndicator(
                progress = { ((downloaded ?: 0).toFloat() / service.model.totalBytes).coerceIn(0f, 1f) },
            )
            Text(stringResource(R.string.read_aloud_downloading, downloaded ?: 0, service.model.totalBytes))
            TextButton(service::leave) { Text(stringResource(R.string.read_aloud_cancel_download)) }
        } else if (!ready) {
            TextButton({
                failed = false
                service.download()
            }, enabled = !busy, modifier = Modifier.testTag("offline-tts-download")) {
                Text(stringResource(R.string.read_aloud_download))
            }
        }
        SpeechResourceActions(installed, busy, "offline-tts", {
            failed = false
            service.download()
        }) {
            scope.launch {
                removing = true
                failed = false
                try {
                    service.removeDownloadedModel()
                    installed = service.model.installedBytes()
                    ready = service.model.ready() != null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failed = true
                } finally {
                    removing = false
                }
            }
        }
    }
}

private fun cancelTtsDownload(service: ReadAloudService) {
    if (service.state.value.phase == ReadAloudPhase.DOWNLOADING) service.leave()
}

private fun ttsModelStatus(
    checking: Boolean,
    ready: Boolean,
) = when {
    checking -> R.string.asr_model_checking
    ready -> R.string.speech_resource_ready
    else -> R.string.speech_resource_missing
}
