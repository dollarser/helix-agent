package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.helix.app.R
import com.helix.app.voice.SystemVoiceSupport
import com.helix.app.voice.VoiceInputMapper

@Composable
@Suppress("FunctionName")
internal fun SystemVoiceSettingsEntry() {
    var open by remember { mutableStateOf(false) }
    TextButton({ open = true }) { Text(stringResource(R.string.system_voice_title)) }
    if (open) SystemVoiceDialog(onDismiss = { open = false })
}

@Composable
@Suppress("FunctionName", "TooGenericExceptionCaught")
internal fun SystemVoiceDialog(
    failure: VoiceInputMapper.Failure? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val support = remember(context) { SystemVoiceSupport(context) }
    var status by remember { mutableStateOf<SystemVoiceSupport.Status?>(null) }
    var failed by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        try {
            status = support.inspect()
            failed = false
        } catch (_: RuntimeException) {
            failed = true
        }
    }
    ConversationSheet(stringResource(R.string.system_voice_title), "system-voice", onDismiss) {
        Column {
            failure?.let { Text(stringResource(voiceFailureText(it)), color = MaterialTheme.colorScheme.error) }
            status?.let { state ->
                Text(
                    stringResource(
                        when {
                            state.inputActivity -> R.string.system_voice_input_available
                            state.recognitionService -> R.string.system_voice_service_only
                            else -> R.string.chat_voice_unavailable
                        },
                    ),
                )
                Text(stringResource(R.string.system_voice_tts_count, state.ttsEngines))
            }
            Text(stringResource(R.string.system_voice_language_hint))
            Text(stringResource(R.string.system_voice_privacy))
            TextButton({ failed = !support.openSettings(false) }) {
                Text(stringResource(R.string.system_voice_input_settings))
            }
            TextButton({ failed = !support.openSettings(true) }) {
                Text(stringResource(R.string.system_voice_tts_settings))
            }
            if (failed) {
                Text(
                    stringResource(R.string.system_voice_settings_failed),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

internal fun voiceFailureText(failure: VoiceInputMapper.Failure): Int =
    when (failure) {
        VoiceInputMapper.Failure.NO_MATCH -> R.string.system_voice_no_match
        VoiceInputMapper.Failure.AUDIO -> R.string.system_voice_audio_error
        VoiceInputMapper.Failure.NETWORK -> R.string.system_voice_network_error
        VoiceInputMapper.Failure.SERVICE -> R.string.system_voice_service_error
    }
