package com.helix.app.ui

internal data class ComposerActions(
    val onAttach: () -> Unit,
    val onVoice: () -> Unit,
    val onSend: () -> Unit,
    val onStop: () -> Unit,
)
