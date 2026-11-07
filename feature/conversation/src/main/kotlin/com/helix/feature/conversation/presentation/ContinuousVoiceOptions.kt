package com.helix.feature.conversation.presentation

data class ContinuousVoiceOptions(
    val enabled: Boolean = false,
    val onCommitted: (String) -> Unit = {},
    val onRestart: () -> Unit = {},
)
