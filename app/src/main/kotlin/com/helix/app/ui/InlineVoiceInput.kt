package com.helix.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.helix.app.R
import com.helix.app.voice.VoiceCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Foreground capture adapter. Preview is transient composer state; only a final result commits a draft. */
@Composable
@Suppress("FunctionName", "LongMethod", "LongParameterList", "CyclomaticComplexMethod")
internal fun InlineVoiceInput(
    onDismiss: () -> Unit,
    onAccept: (String) -> Unit,
    captureFactory: (Context, CoroutineScope) -> VoiceCapture,
    account: com.helix.app.account.AccountSession,
    speechSettings: com.helix.app.voice.SpeechSettingsStore,
    consent: com.helix.app.voice.SpeechConsentStore,
    onStopSpeech: suspend () -> Unit,
    onPreview: (String) -> Unit = {},
    onError: (String) -> Unit = {},
    permissionChecker: (Context) -> Boolean = { context ->
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    },
    dictation: ContinuousVoiceOptions = ContinuousVoiceOptions(),
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val capture = remember { captureFactory(context.applicationContext, scope) }
    val accountIdentity by account.identityState.collectAsState()
    val accountRequired =
        remember(capture) {
            speechSettings.forAccount(account.status.value).requiresAccount
        }
    val consentIdentity =
        remember(capture) {
            if (accountRequired) account.identity else "custom-service"
        }
    val state by capture.state.collectAsState()
    val preview by rememberUpdatedState(onPreview)
    val accept by rememberUpdatedState(onAccept)
    val dismiss by rememberUpdatedState(onDismiss)
    val error by rememberUpdatedState(onError)
    val committed by rememberUpdatedState(dictation.onCommitted)
    var lastCommitted by remember(capture) { mutableStateOf("") }
    var permission by remember { mutableStateOf(permissionChecker(context)) }
    var uploadConsent by remember(capture) {
        mutableStateOf(initialVoiceConsent(capture, consent, consentIdentity))
    }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permission = granted
            if (!granted) {
                error(resources.getString(R.string.offline_voice_permission))
                dismiss()
            }
        }

    fun commitPending() {
        val completed = capture.state.value.committedText
        if (completed.isNotBlank() && completed != lastCommitted) {
            committed(completed)
            lastCommitted = completed
        }
    }

    fun cancel() {
        commitPending()
        capture.cancel()
        dismiss()
    }
    LaunchedEffect(accountIdentity) {
        if (voiceIdentityChanged(accountRequired, consentIdentity, accountIdentity)) cancel()
    }
    BackHandler(onBack = ::cancel)
    DisposableEffect(capture) { onDispose { capture.cancel() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        commitPending()
        capture.cancel(VoiceCapture.Failure.INTERRUPTED)
    }
    LaunchedEffect(permission, uploadConsent) {
        if (permission && uploadConsent) {
            capture.start(
                segmenter = if (dictation.enabled) ({ dictation.segmenterFactory(context) }) else null,
            ) { onStopSpeech() }
        }
    }
    LaunchedEffect(state) {
        if (state.committedText.isNotBlank() && state.committedText != lastCommitted) {
            committed(state.committedText)
            lastCommitted = state.committedText
        }
        when (state.phase) {
            VoiceCapture.Phase.LISTENING, VoiceCapture.Phase.FINISHING -> {
                preview(state.text)
            }

            VoiceCapture.Phase.REVIEW -> {
                acceptVoiceReview(state.text, preview, accept, dismiss)
            }

            VoiceCapture.Phase.FAILED -> {
                preview("")
                if (state.failure == VoiceCapture.Failure.INTERRUPTED) {
                    error(resources.getString(voiceStatusText(state, permission)))
                    dismiss()
                }
            }

            else -> {
                Unit
            }
        }
    }
    InlineVoiceControls(
        state,
        permission,
        uploadConsent,
        capture.uploadsAudio,
        capture.destination,
        capture.supportsPartial,
        onCancel = ::cancel,
        onConsent = {
            if (approveVoiceConsent(capture, consent, consentIdentity)) {
                uploadConsent = true
            } else {
                error(resources.getString(R.string.speech_consent_failed))
                dismiss()
            }
        },
        onGrant = { launcher.launch(Manifest.permission.RECORD_AUDIO) },
        onFinish = capture::finish,
        onRetry = {
            dictation.onRestart()
            lastCommitted = ""
            scope.launch { capture.retry() }
        },
        continuous = dictation.enabled,
    )
}

/** Stateless controls: recording/permission ownership stays in the foreground adapter above. */
@Composable
@Suppress("FunctionName", "LongParameterList")
internal fun InlineVoiceControls(
    state: VoiceCapture.State,
    permission: Boolean,
    uploadConsent: Boolean,
    uploadsAudio: Boolean,
    destination: String?,
    supportsPartial: Boolean,
    onCancel: () -> Unit,
    onConsent: () -> Unit,
    onGrant: () -> Unit,
    onFinish: () -> Unit,
    onRetry: () -> Unit,
    continuous: Boolean = false,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag("voice-inline"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (continuous) Text(stringResource(R.string.voice_continuous_hint))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (permission && uploadConsent &&
                state.processing
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            Text(
                stringResource(voiceStatusText(state, permission)),
                Modifier.testTag("voice-status"),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        VoiceUploadNotice(uploadsAudio && !uploadConsent, destination)
        val finalOnlyCloud = uploadsAudio && uploadConsent && !supportsPartial
        if (finalOnlyCloud && !continuous) {
            Text(stringResource(R.string.speech_http_asr_mode))
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onCancel, Modifier.testTag("voice-cancel")) {
                Text(stringResource(R.string.offline_voice_cancel))
            }
            when {
                !uploadConsent -> {
                    Button(onConsent, Modifier.testTag("voice-upload-consent")) {
                        Text(stringResource(R.string.cloud_voice_confirm))
                    }
                }

                !permission -> {
                    Button(onGrant, Modifier.testTag("voice-grant")) {
                        Text(stringResource(R.string.offline_voice_grant))
                    }
                }

                state.phase == VoiceCapture.Phase.LISTENING -> {
                    Button(onFinish, Modifier.testTag("voice-finish")) {
                        Text(stringResource(R.string.offline_voice_finish))
                    }
                }

                state.retryable -> {
                    Button(onRetry, Modifier.testTag("voice-retry")) {
                        Text(stringResource(R.string.chat_retry))
                    }
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun VoiceUploadNotice(
    visible: Boolean,
    destination: String?,
) {
    if (visible) {
        Text(stringResource(R.string.cloud_voice_upload))
        destination?.let {
            Text(
                java.net
                    .URI(it)
                    .host
                    .orEmpty(),
            )
        }
    }
}

private fun acceptVoiceReview(
    text: String,
    preview: (String) -> Unit,
    accept: (String) -> Unit,
    dismiss: () -> Unit,
) {
    if (text.isBlank()) {
        preview("")
    } else {
        accept(text.trim())
        dismiss()
    }
}

internal fun voiceStatusText(
    state: VoiceCapture.State,
    permission: Boolean,
): Int =
    if (!permission) {
        R.string.offline_voice_permission
    } else {
        when (state.phase) {
            VoiceCapture.Phase.LOADING -> {
                R.string.offline_voice_loading
            }

            VoiceCapture.Phase.LISTENING -> {
                R.string.offline_voice_listening
            }

            VoiceCapture.Phase.FINISHING -> {
                R.string.offline_voice_finishing
            }

            VoiceCapture.Phase.REVIEW -> {
                if (state.text.isBlank()) R.string.offline_voice_empty else R.string.voice_inline_ready
            }

            VoiceCapture.Phase.FAILED -> {
                voiceFailureText(state.failure)
            }

            else -> {
                R.string.offline_voice_stopped
            }
        }
    }

private fun voiceFailureText(failure: VoiceCapture.Failure?): Int =
    when (failure) {
        VoiceCapture.Failure.MODEL -> R.string.offline_voice_model_error
        VoiceCapture.Failure.FOCUS -> R.string.offline_voice_focus_error
        VoiceCapture.Failure.BUSY -> R.string.speech_model_busy
        VoiceCapture.Failure.CONNECTION -> R.string.cloud_voice_connection_error
        VoiceCapture.Failure.BACKPRESSURE -> R.string.voice_continuous_slow
        VoiceCapture.Failure.LIMIT -> R.string.voice_continuous_limit
        else -> R.string.offline_voice_audio_error
    }

private fun initialVoiceConsent(
    capture: VoiceCapture,
    consent: com.helix.app.voice.SpeechConsentStore,
    identity: String,
): Boolean = !capture.uploadsAudio || capture.destination?.let { consent.approved("asr", it, identity) } == true

private fun voiceIdentityChanged(
    required: Boolean,
    expected: String,
    current: String,
): Boolean = required && expected != current

private fun approveVoiceConsent(
    capture: VoiceCapture,
    consent: com.helix.app.voice.SpeechConsentStore,
    identity: String,
): Boolean = capture.destination?.let { consent.approve("asr", it, identity) } == true
