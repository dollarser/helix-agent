package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.helix.app.HelixApplication
import com.helix.app.R
import com.helix.app.voice.OfflineAsrModel

/** Download consent precedes microphone permission; finishing a download never starts recording. */
@Composable
@Suppress("FunctionName")
internal fun OfflineAsrGate(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val app = LocalContext.current.applicationContext as HelixApplication
    if (app.speechSettings.read().httpEnabled) {
        content()
        return
    }
    OfflineAsrGateContent(app.offlineAsr, onDismiss, content)
}

@Composable
@Suppress("FunctionName")
internal fun OfflineAsrGateContent(
    model: OfflineAsrModel,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val phase by model.phase.collectAsState()
    var prompted by remember(model) { mutableStateOf(false) }
    var continueCapture by remember(model) { mutableStateOf(false) }
    LaunchedEffect(model) { model.refresh() }
    DisposableEffect(model) { onDispose { model.cancel() } }
    if (phase == OfflineAsrModel.Phase.READY && (!prompted || continueCapture)) {
        content()
    } else {
        if (phase != OfflineAsrModel.Phase.CHECKING) SideEffect { prompted = true }
        OfflineAsrModelPanel(model, onContinue = { continueCapture = true }) {
            model.cancel()
            onDismiss()
        }
    }
}

@Composable
@Suppress("FunctionName", "LongMethod")
internal fun OfflineAsrModelPanel(
    model: OfflineAsrModel,
    onContinue: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
) {
    val phase by model.phase.collectAsState()
    val bytes by model.downloadedBytes.collectAsState()
    LaunchedEffect(model) { model.refresh() }
    DisposableEffect(model) { onDispose { model.cancel() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { model.cancel() }
    Column(Modifier.padding(12.dp).testTag("offline-asr-model")) {
        Text(stringResource(R.string.asr_model_title))
        Text(stringResource(R.string.asr_model_description, (model.totalBytes + 1048575) / 1048576))
        Text(
            stringResource(phase.labelRes()),
            Modifier.testTag("offline-asr-model-status"),
        )
        if (phase == OfflineAsrModel.Phase.DOWNLOADING) {
            LinearProgressIndicator(progress = { ((bytes ?: 0).toFloat() / model.totalBytes).coerceIn(0f, 1f) })
            Text(stringResource(R.string.read_aloud_downloading, bytes ?: 0, model.totalBytes))
        }
        Row {
            if (phase == OfflineAsrModel.Phase.MISSING || phase == OfflineAsrModel.Phase.FAILED) {
                TextButton(model::download, Modifier.testTag("offline-asr-download")) {
                    Text(stringResource(R.string.asr_model_download))
                }
            }
            if (phase == OfflineAsrModel.Phase.DOWNLOADING) {
                TextButton(model::cancel, Modifier.testTag("offline-asr-download-cancel")) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
            if (phase == OfflineAsrModel.Phase.READY && onContinue != null) {
                TextButton(onContinue, Modifier.testTag("offline-asr-continue")) {
                    Text(stringResource(R.string.asr_model_start))
                }
            }
            if (onCancel != null) {
                TextButton(onCancel, Modifier.testTag("offline-asr-close")) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        }
    }
}

private fun OfflineAsrModel.Phase.labelRes(): Int =
    when (this) {
        OfflineAsrModel.Phase.CHECKING -> R.string.asr_model_checking
        OfflineAsrModel.Phase.MISSING -> R.string.asr_model_missing
        OfflineAsrModel.Phase.DOWNLOADING -> R.string.asr_model_downloading
        OfflineAsrModel.Phase.READY -> R.string.asr_model_ready
        OfflineAsrModel.Phase.FAILED -> R.string.asr_model_failed
    }
