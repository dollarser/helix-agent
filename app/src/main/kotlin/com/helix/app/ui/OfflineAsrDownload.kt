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
import com.helix.app.account.AccountStatus
import com.helix.app.voice.OfflineAsrModel

/** Download consent precedes microphone permission; finishing a download never starts recording. */
@Composable
@Suppress("FunctionName", "LongMethod") // Resolve account routing before microphone or download UI.
internal fun OfflineAsrGate(
    onDismiss: () -> Unit,
    onError: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    val app = LocalContext.current.applicationContext as HelixApplication
    val resources = androidx.compose.ui.platform.LocalResources.current
    val signedIn by app.account.signedIn.collectAsState()
    val accountStatus by app.account.status.collectAsState()
    val stored = app.speechSettings.read()
    val needsAccount = app.speechSettings.automatic || stored.requiresAccount
    var routingReady by remember { mutableStateOf(!needsAccount) }
    var accountUnavailable by remember { mutableStateOf(false) }
    var usedAccount by remember { mutableStateOf(false) }
    LaunchedEffect(needsAccount) {
        if (needsAccount) {
            try {
                app.account.restore()
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: java.io.IOException) {
                accountUnavailable = true
            }
        }
        routingReady = true
    }
    val settings = app.speechSettings.forAccount(accountStatus)
    val error =
        speechRoutingError(
            settings,
            if (needsAccount) accountStatus else null,
            accountUnavailable,
            usedAccount,
            signedIn,
        )
    when {
        !routingReady -> {
            Text(stringResource(R.string.offline_voice_loading))
        }

        error != null -> {
            LaunchedEffect(error) {
                onError(resources.getString(error))
                onDismiss()
            }
        }

        settings.httpEnabled -> {
            SideEffect { if (settings.useAccountLogin) usedAccount = true }
            content()
        }

        else -> {
            OfflineAsrGateContent(app.offlineAsr, onDismiss, content)
        }
    }
}

internal fun speechRoutingError(
    settings: com.helix.app.voice.SpeechServiceSettings,
    accountStatus: AccountStatus?,
    restoreFailed: Boolean,
    usedAccount: Boolean,
    signedIn: Boolean,
): Int? =
    when {
        runCatching { settings.validate() }.isFailure -> R.string.speech_settings_invalid

        accountStatus == AccountStatus.UNAVAILABLE ||
            (accountStatus != null && restoreFailed) -> R.string.speech_account_unavailable

        (settings.requiresAccount || usedAccount || accountStatus == AccountStatus.REAUTH_REQUIRED) &&
            !signedIn -> R.string.speech_account_required

        else -> null
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
    val installed by model.installedBytes.collectAsState()
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
                    Text(stringResource(R.string.read_aloud_cancel_download))
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
        SpeechResourceActions(
            installed,
            phase == OfflineAsrModel.Phase.CHECKING || phase == OfflineAsrModel.Phase.DOWNLOADING,
            "offline-asr",
            model::download,
            model::remove,
        )
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
