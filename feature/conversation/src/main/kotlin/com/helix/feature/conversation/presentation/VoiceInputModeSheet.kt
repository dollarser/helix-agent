package com.helix.feature.conversation.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.feature.conversation.R
import com.helix.ui.shared.SharedSheet

/** Mode selection precedes capture, microphone permission and upload consent; cancel keeps the draft. */
@Composable
@Suppress("FunctionName")
fun VoiceInputModeSheet(
    onDismiss: () -> Unit,
    onChoose: (Boolean) -> Unit,
) {
    SharedSheet(stringResource(R.string.voice_input_modes_title), "voice-input-modes", onDismiss) {
        ConversationMenuRow(
            stringResource(R.string.voice_input_single),
            { onChoose(false) },
            Modifier.testTag("voice-input-single"),
        )
        ConversationMenuRow(
            stringResource(R.string.voice_continuous_title),
            { onChoose(true) },
            Modifier.testTag("voice-input-continuous"),
            supporting = stringResource(R.string.voice_continuous_hint),
        )
    }
}
