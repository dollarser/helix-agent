package com.helix.app.ui

import android.content.Context

internal data class ContinuousVoiceOptions(
    val enabled: Boolean = false,
    val onCommitted: (String) -> Unit = {},
    val onRestart: () -> Unit = {},
    val segmenterFactory: (Context) -> com.helix.app.voice.SpeechSegments = {
        com.helix.app.voice
            .SileroSpeechSegments(it)
    },
)
