package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.helix.app.HelixApplication
import com.helix.app.R

@Composable
@Suppress("FunctionName", "LongMethod") // One existing settings sheet; transport selection keeps fields together.
internal fun CloudSpeechSettingsEntry() {
    val application = LocalContext.current.applicationContext as HelixApplication
    var open by remember { mutableStateOf(false) }
    SettingsEntryRow(stringResource(R.string.speech_services_title), R.drawable.ic_composer_voice, { open = true })
    if (open) {
        var draft by remember { mutableStateOf(application.speechSettings.read()) }
        var error by remember { mutableStateOf(false) }
        ConversationSheet(stringResource(R.string.speech_services_title), "speech-services", { open = false }) {
            Column {
                OfflineAsrModelPanel(application.offlineAsr)
                SpeechSettingToggle(stringResource(R.string.speech_ws_enable), draft.webSocket, { enabled ->
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
                SpeechSettingToggle(stringResource(R.string.speech_http_enable), draft.httpEnabled, {
                    draft =
                        draft.copy(httpEnabled = it)
                })
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
                SpeechSettingToggle(
                    stringResource(R.string.speech_use_account_login),
                    draft.useAccountLogin,
                    { draft = draft.copy(useAccountLogin = it) },
                    enabled = !draft.webSocket,
                )
                if (draft.useAccountLogin) Text(stringResource(R.string.speech_account_setup_hint))
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

/** One labelled 48dp switch target; changing it only updates this sheet's unsaved draft. */
@Composable
@Suppress("FunctionName")
internal fun SpeechSettingToggle(
    label: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onChecked,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}
