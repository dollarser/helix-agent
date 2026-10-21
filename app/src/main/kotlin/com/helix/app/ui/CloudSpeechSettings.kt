package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.helix.app.HelixApplication
import com.helix.app.R

@Composable
@Suppress("FunctionName", "LongMethod") // One existing settings sheet; transport selection keeps fields together.
internal fun CloudSpeechSettingsEntry() {
    val application = LocalContext.current.applicationContext as HelixApplication
    var open by remember { mutableStateOf(false) }
    TextButton({ open = true }) { Text(stringResource(R.string.speech_services_title)) }
    if (open) {
        var draft by remember { mutableStateOf(application.speechSettings.read()) }
        var error by remember { mutableStateOf(false) }
        ConversationSheet(stringResource(R.string.speech_services_title), "speech-services", { open = false }) {
            Column {
                Text(stringResource(R.string.speech_ws_enable))
                Switch(draft.webSocket, { enabled ->
                    draft =
                        draft.copy(
                            webSocket = enabled,
                            useAccountLogin = if (enabled) true else draft.useAccountLogin,
                            asrEndpoint =
                                if (enabled) {
                                    "wss://asr.sunlingzhang.com/ws/asr"
                                } else {
                                    "https://asr.sunlingzhang.com/v1/audio/transcriptions"
                                },
                            ttsEndpoint =
                                if (enabled) {
                                    "wss://tts.sunlingzhang.com/ws/tts"
                                } else {
                                    "https://tts.sunlingzhang.com/v1/audio/speech"
                                },
                        )
                })
                Text(
                    stringResource(
                        if (draft.webSocket) R.string.speech_ws_description else R.string.speech_http_description,
                    ),
                )
                Text(stringResource(R.string.speech_http_loopback))
                Text(stringResource(R.string.speech_http_enable))
                Switch(draft.httpEnabled, { draft = draft.copy(httpEnabled = it) })
                OutlinedTextField(
                    draft.asrEndpoint,
                    { draft = draft.copy(asrEndpoint = it) },
                    label = { Text(stringResource(R.string.speech_asr_endpoint)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    draft.ttsEndpoint,
                    { draft = draft.copy(ttsEndpoint = it) },
                    label = { Text(stringResource(R.string.speech_tts_endpoint)) },
                    singleLine = true,
                )
                Text(stringResource(R.string.speech_use_account_login))
                Switch(draft.useAccountLogin, { draft = draft.copy(useAccountLogin = it) }, enabled = !draft.webSocket)
                if (error) Text(stringResource(R.string.speech_settings_invalid))
                TextButton({
                    try {
                        application.readAloud.leave()
                        application.speechSettings.save(draft)
                        open = false
                    } catch (_: IllegalArgumentException) {
                        error = true
                    } catch (_: IllegalStateException) {
                        error = true
                    }
                }) { Text(stringResource(R.string.speech_settings_save)) }
            }
        }
    }
}
