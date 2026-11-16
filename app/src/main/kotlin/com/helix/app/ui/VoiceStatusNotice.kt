package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.app.voice.VoiceCapture

@Composable
@Suppress("FunctionName")
internal fun VoiceStatusNotice(
    state: VoiceCapture.State,
    permission: Boolean,
) {
    if (state.phase == VoiceCapture.Phase.FAILED && state.errorDetails != null) {
        AccountFailureNotice(voiceStatusText(state, permission), state.errorDetails)
    } else {
        Text(
            stringResource(voiceStatusText(state, permission)),
            Modifier.testTag("voice-status"),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
