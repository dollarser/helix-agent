package com.helix.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.helix.app.R
import com.helix.app.voice.VoiceCapture

@Composable
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod")
internal fun OfflineVoiceDialog(
    onDismiss: () -> Unit,
    onAccept: (String) -> Unit,
    captureFactory: (android.content.Context, kotlinx.coroutines.CoroutineScope) -> VoiceCapture = { context, scope ->
        val application = context.applicationContext as com.helix.app.HelixApplication
        VoiceCapture(context, scope, application.speechSettings.asrFactory(context))
    },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val capture = remember { captureFactory(context.applicationContext, scope) }
    val state by capture.state.collectAsState()
    var permission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionDenied by remember { mutableStateOf(false) }
    var uploadConsent by remember(capture) { mutableStateOf(!capture.uploadsAudio) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            permission = it
            permissionDenied = !it
        }
    DisposableEffect(capture) { onDispose { capture.cancel() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (state.phase in
            setOf(VoiceCapture.Phase.LOADING, VoiceCapture.Phase.LISTENING, VoiceCapture.Phase.FINISHING)
        ) {
            capture.cancel(VoiceCapture.Failure.INTERRUPTED)
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        permission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }
    LaunchedEffect(permission, uploadConsent) { if (permission && uploadConsent) capture.start() }
    var review by remember { mutableStateOf("") }
    LaunchedEffect(state.phase) { if (state.phase == VoiceCapture.Phase.REVIEW) review = state.text }
    val reviewing = state.phase == VoiceCapture.Phase.REVIEW
    val active =
        state.phase in
            setOf(
                VoiceCapture.Phase.LOADING,
                VoiceCapture.Phase.LISTENING,
                VoiceCapture.Phase.FINISHING,
            )
    AlertDialog(
        onDismissRequest = {
            capture.cancel()
            onDismiss()
        },
        title = {
            val title = if (capture.uploadsAudio) R.string.speech_http_input_title else R.string.offline_voice_title
            Text(stringResource(title))
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(
                        if (capture.uploadsAudio) R.string.cloud_voice_upload else R.string.offline_voice_privacy,
                    ),
                )
                if (capture.uploadsAudio && !capture.supportsPartial) {
                    Text(stringResource(R.string.speech_http_asr_mode))
                }
                capture.destination?.let { Text(it) }
                val status =
                    if (capture.uploadsAudio && permission && state.phase == VoiceCapture.Phase.LOADING) {
                        R.string.speech_service_preparing
                    } else {
                        voiceStatusText(state, permission)
                    }
                Text(stringResource(status))
                if (permissionDenied && !permission) {
                    TextButton({
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                "package:${context.packageName}".toUri(),
                            ),
                        )
                    }) { Text(stringResource(R.string.offline_voice_app_settings)) }
                }
                if (reviewing) {
                    OutlinedTextField(
                        value = review,
                        onValueChange = { review = it },
                        modifier = Modifier.testTag("voice-review"),
                        label = { Text(stringResource(R.string.offline_voice_transcript)) },
                        minLines = 3,
                        maxLines = 6,
                    )
                } else if (state.text.isNotBlank()) {
                    Text(state.text.takeLast(300), modifier = Modifier.testTag("voice-partial"))
                }
            }
        },
        confirmButton = {
            when {
                !uploadConsent -> {
                    TextButton({ uploadConsent = true }) { Text(stringResource(R.string.cloud_voice_confirm)) }
                }

                !permission -> {
                    TextButton(
                        { launcher.launch(Manifest.permission.RECORD_AUDIO) },
                        modifier = Modifier.testTag("voice-grant"),
                    ) {
                        Text(stringResource(R.string.offline_voice_grant))
                    }
                }

                reviewing -> {
                    TextButton(
                        {
                            onAccept(review.trim())
                            onDismiss()
                        },
                        enabled = review.isNotBlank(),
                        modifier = Modifier.testTag("voice-accept"),
                    ) { Text(stringResource(R.string.offline_voice_accept)) }
                }

                state.phase == VoiceCapture.Phase.LISTENING -> {
                    TextButton(
                        { capture.finish() },
                        modifier = Modifier.testTag("voice-finish"),
                    ) { Text(stringResource(R.string.offline_voice_finish)) }
                }

                !active -> {
                    TextButton({ capture.start() }) { Text(stringResource(R.string.offline_voice_retry)) }
                }
            }
        },
        dismissButton = {
            TextButton({
                capture.cancel()
                onDismiss()
            }, modifier = Modifier.testTag("voice-cancel")) {
                Text(stringResource(R.string.offline_voice_cancel))
            }
        },
    )
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
                if (state.text.isBlank()) R.string.offline_voice_empty else R.string.offline_voice_review
            }

            VoiceCapture.Phase.FAILED -> {
                when (state.failure) {
                    VoiceCapture.Failure.MODEL -> R.string.offline_voice_model_error
                    VoiceCapture.Failure.FOCUS -> R.string.offline_voice_focus_error
                    VoiceCapture.Failure.BUSY -> R.string.speech_model_busy
                    VoiceCapture.Failure.CONNECTION -> R.string.cloud_voice_connection_error
                    else -> R.string.offline_voice_audio_error
                }
            }

            else -> {
                R.string.offline_voice_stopped
            }
        }
    }
