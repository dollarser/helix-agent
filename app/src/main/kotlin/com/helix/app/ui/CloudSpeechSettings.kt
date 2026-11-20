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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.ui.shared.SharedSheet
import com.helix.ui.shared.R as SharedR

@Composable
@Suppress("FunctionName", "LongMethod") // One existing settings sheet; transport selection keeps fields together.
internal fun CloudSpeechSettingsEntry(
    settings: com.helix.app.voice.SpeechSettingsStore,
    account: com.helix.app.account.AccountSession,
    offlineAsr: com.helix.app.voice.OfflineAsrModel,
    reader: com.helix.app.voice.ReadAloudService,
) {
    var open by remember { mutableStateOf(false) }
    SettingsEntryRow(
        stringResource(R.string.speech_services_title),
        SharedR.drawable.ic_composer_voice,
        { open = true },
    )
    if (open) {
        var draft by remember {
            mutableStateOf(
                settings.forAccount(account.status.value),
            )
        }
        var error by remember { mutableStateOf(false) }
        var providerLoadFailed by remember { mutableStateOf(false) }
        var providers by remember { mutableStateOf(emptyList<com.helix.app.voice.SpeechProviderOption>()) }
        LaunchedEffect(settings) {
            try {
                providers = settings.availableProviders()
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                providerLoadFailed = true
            }
        }
        SharedSheet(stringResource(R.string.speech_services_title), "speech-services", { open = false }) {
            Column {
                Text(stringResource(R.string.speech_local_section))
                OfflineAsrModelPanel(offlineAsr)
                OfflineTtsModelPanel(reader)
                Text(stringResource(R.string.speech_cloud_section))
                SpeechSettingToggle(stringResource(R.string.speech_http_enable), draft.httpEnabled, {
                    draft = draft.copy(httpEnabled = it)
                })
                if (draft.httpEnabled) {
                    val selfHostedLabel = stringResource(R.string.cloud_speech_self_hosted_ws)
                    SpeechSettingToggle(selfHostedLabel, draft.webSocket, { enabled ->
                        draft = speechTransportSelection(draft, enabled)
                    })
                    Text(
                        stringResource(
                            if (draft.webSocket) {
                                R.string.cloud_speech_self_hosted_hint
                            } else {
                                R.string.speech_http_description
                            },
                        ),
                    )
                    if (!draft.useAccountLogin && draft.providerId == null) {
                        SpeechCustomEndpoints(draft) { draft = it }
                    }
                    SpeechSettingToggle(
                        stringResource(R.string.speech_use_account_login),
                        draft.useAccountLogin,
                        { draft = draft.copy(useAccountLogin = it, providerId = null) },
                        enabled = !draft.webSocket,
                    )
                    if (providerLoadFailed) Text(stringResource(R.string.cloud_speech_providers_failed))
                    SpeechProviderChoices(providers, draft) { draft = it }
                    if (draft.useAccountLogin || draft.providerId != null) {
                        SpeechModelFields(draft) { draft = it }
                    }
                }
                if (error) Text(stringResource(R.string.speech_settings_invalid))
                TextButton({
                    try {
                        reader.leave()
                        settings.save(draft)
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

@Composable
@Suppress("FunctionName")
private fun SpeechModelFields(
    draft: com.helix.app.voice.SpeechServiceSettings,
    onChange: (com.helix.app.voice.SpeechServiceSettings) -> Unit,
) {
    if (draft.useAccountLogin) Text(stringResource(R.string.speech_account_setup_hint))
    OutlinedTextField(
        draft.asrModel,
        { onChange(draft.copy(asrModel = it.trim())) },
        label = { Text(stringResource(R.string.cloud_speech_asr_model)) },
        singleLine = true,
    )
    Text(
        stringResource(
            if (draft.asrWebSocket) {
                R.string.cloud_speech_route_ws
            } else {
                R.string.cloud_speech_route_http
            },
        ),
    )
    OutlinedTextField(
        draft.ttsModel,
        { onChange(draft.copy(ttsModel = it.trim())) },
        label = { Text(stringResource(R.string.cloud_speech_tts_model)) },
        singleLine = true,
    )
    Text(
        stringResource(
            if (draft.ttsWebSocket) {
                R.string.cloud_speech_route_ws
            } else {
                R.string.cloud_speech_route_http
            },
        ),
    )
}

@Composable
@Suppress("FunctionName")
private fun SpeechCustomEndpoints(
    draft: com.helix.app.voice.SpeechServiceSettings,
    onChange: (com.helix.app.voice.SpeechServiceSettings) -> Unit,
) {
    Text(stringResource(R.string.speech_http_loopback))
    OutlinedTextField(
        draft.customAsrEndpoint,
        { onChange(draft.copy(customAsrEndpoint = it)) },
        label = { Text(stringResource(R.string.speech_asr_endpoint)) },
        singleLine = true,
    )
    OutlinedTextField(
        draft.customTtsEndpoint,
        { onChange(draft.copy(customTtsEndpoint = it)) },
        label = { Text(stringResource(R.string.speech_tts_endpoint)) },
        singleLine = true,
    )
}

private fun speechTransportSelection(
    draft: com.helix.app.voice.SpeechServiceSettings,
    enabled: Boolean,
) = draft.copy(
    webSocket = enabled,
    providerId = if (enabled) null else draft.providerId,
    useAccountLogin = if (enabled) true else draft.useAccountLogin,
    asrModel = if (enabled) "self-hosted" else "gpt-transcribe",
    ttsModel = if (enabled) "self-hosted" else "gpt-realtime-2.1-mini",
)

@Composable
@Suppress("FunctionName")
private fun SpeechProviderChoices(
    providers: List<com.helix.app.voice.SpeechProviderOption>,
    draft: com.helix.app.voice.SpeechServiceSettings,
    onChange: (com.helix.app.voice.SpeechServiceSettings) -> Unit,
) {
    if (providers.isNotEmpty()) {
        Text(stringResource(R.string.cloud_speech_api_services))
        providers.forEach { provider ->
            TextButton({
                onChange(
                    draft.copy(
                        providerId = provider.id,
                        useAccountLogin = false,
                        webSocket = false,
                        customAsrEndpoint = provider.asrEndpoint,
                        customTtsEndpoint = provider.ttsEndpoint,
                        asrModel = "gpt-transcribe",
                        ttsModel = "gpt-realtime-2.1-mini",
                    ),
                )
            }) {
                Text(
                    if (draft.providerId == provider.id) {
                        "✓ ${provider.displayName}"
                    } else {
                        provider.displayName
                    },
                )
            }
        }
    }
}
