package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.HelixApplication
import com.helix.app.R
import com.helix.app.account.AccountStatus
import com.helix.app.voice.ReadAloudPhase
import com.helix.app.voice.ReadAloudService

@Composable
@Suppress("FunctionName")
internal fun ReadAloudLifecycle(
    service: ReadAloudService?,
    sessionId: String?,
) {
    val context = LocalContext.current
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) { service?.leave() }
    androidx.compose.runtime.DisposableEffect(sessionId, service) {
        val receiver =
            object : android.content.BroadcastReceiver() {
                override fun onReceive(
                    context: android.content.Context,
                    intent: android.content.Intent,
                ) {
                    if (intent.action == android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY) service?.stop()
                }
            }
        androidx.core.content.ContextCompat.registerReceiver(
            context,
            receiver,
            android.content.IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose {
            service?.leave()
            context.unregisterReceiver(receiver)
        }
    }
}

@Composable
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod") // Local resource/player UI state mapping.
internal fun ReadAloudAction(
    id: String,
    text: String,
    supplied: ReadAloudService? = null,
    turnId: String? = null,
    onStart: (() -> Unit)? = null,
) {
    val service = supplied ?: (LocalContext.current.applicationContext as? HelixApplication)?.readAloud ?: return
    val application = LocalContext.current.applicationContext as? HelixApplication
    val consent = application?.speechConsent
    val consentIdentity = service.accountIdentity ?: "custom-service"
    val accountIdentity by (
        application?.account?.identityState
            ?: kotlinx.coroutines.flow.MutableStateFlow("signed-out")
    ).collectAsStateWithLifecycle()
    LaunchedEffect(accountIdentity, service) {
        if (service.accountIdentity != null && accountIdentity != service.accountIdentity) service.stop()
    }
    val accountStatus =
        application
            ?.account
            ?.status
            ?.collectAsStateWithLifecycle()
            ?.value
    val accountReady =
        service.accountIdentity == null ||
            (accountStatus == AccountStatus.SIGNED_IN && accountIdentity == service.accountIdentity)
    val state by service.state.collectAsStateWithLifecycle()
    val bytes by service.model.downloadedBytes.collectAsStateWithLifecycle()
    var details by remember(id) { mutableStateOf(false) }
    var uploadConfirmation by remember(id, service) { mutableStateOf(false) }
    var consentFailed by remember(id, service) { mutableStateOf(false) }
    val matches = state.messageId == id || (turnId != null && service.livePlaybackTurnId == turnId)
    val active =
        matches && state.phase in
            setOf(ReadAloudPhase.PREPARING, ReadAloudPhase.SPEAKING, ReadAloudPhase.STOPPING)
    LaunchedEffect(state) {
        if (matches && state.phase in
            setOf(ReadAloudPhase.MISSING, ReadAloudPhase.FAILED, ReadAloudPhase.UNAVAILABLE)
        ) {
            details = true
        }
    }
    IconButton(
        onClick = {
            when {
                active -> service.stop()

                !accountReady -> details = true

                service.uploadsText && service.uploadDestination?.let {
                    consent?.approved("tts", it, consentIdentity)
                } != true -> uploadConfirmation = true

                else -> if (onStart == null) service.play(id, text) else onStart()
            }
        },
        enabled = text.isNotBlank() && state.phase !in setOf(ReadAloudPhase.DOWNLOADING, ReadAloudPhase.STOPPING),
        modifier = Modifier.testTag("chat-read-$id"),
    ) {
        Icon(
            painterResource(if (active) R.drawable.ic_composer_stop else R.drawable.ic_read_aloud),
            stringResource(if (active) R.string.read_aloud_stop else R.string.read_aloud_start),
        )
    }
    if (uploadConfirmation) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { uploadConfirmation = false },
            title = { Text(stringResource(R.string.read_aloud_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.cloud_tts_upload))
                    if (consentFailed) Text(stringResource(R.string.speech_consent_failed))
                    service.uploadDestination?.let {
                        Text(
                            java.net
                                .URI(it)
                                .host
                                .orEmpty(),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton({
                    consentFailed =
                        service.uploadDestination?.let { consent?.approve("tts", it, consentIdentity) } != true
                    if (!consentFailed) {
                        uploadConfirmation = false
                        if (onStart == null) service.play(id, text) else onStart()
                    }
                }) {
                    Text(stringResource(R.string.cloud_voice_confirm))
                }
            },
            dismissButton = {
                TextButton({ uploadConfirmation = false }) { Text(stringResource(R.string.offline_voice_cancel)) }
            },
        )
    }
    if (details) {
        ConversationSheet(stringResource(R.string.read_aloud_title), "read-aloud", {
            service.leave()
            details = false
        }) {
            Column {
                if (!accountReady) {
                    Text(
                        stringResource(
                            if (accountStatus == AccountStatus.UNAVAILABLE) {
                                R.string.speech_account_unavailable
                            } else {
                                R.string.speech_account_required
                            },
                        ),
                    )
                }
                if (state.phase == ReadAloudPhase.FAILED) {
                    val error =
                        if (state.busy) {
                            R.string.speech_model_busy
                        } else if (service.uploadsText) {
                            R.string.cloud_voice_connection_error
                        } else {
                            R.string.read_aloud_failed
                        }
                    Text(stringResource(error))
                }
                if (state.phase == ReadAloudPhase.UNAVAILABLE) Text(stringResource(R.string.read_aloud_unavailable))
                if (state.phase == ReadAloudPhase.DOWNLOADING) {
                    LinearProgressIndicator(progress = { (bytes ?: 0).toFloat() / service.model.totalBytes })
                    Text(stringResource(R.string.read_aloud_downloading, bytes ?: 0, service.model.totalBytes))
                    TextButton({ service.leave() }, Modifier.testTag("read-aloud-download-cancel")) {
                        Text(stringResource(R.string.read_aloud_cancel_download))
                    }
                } else if (state.phase == ReadAloudPhase.READY) {
                    Text(stringResource(R.string.read_aloud_ready))
                } else if (state.phase == ReadAloudPhase.MISSING ||
                    (state.phase == ReadAloudPhase.FAILED && state.messageId == null)
                ) {
                    TextButton({ service.download() }, Modifier.testTag("read-aloud-download")) {
                        Text(stringResource(R.string.read_aloud_download))
                    }
                }
            }
        }
    }
}
