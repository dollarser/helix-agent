package com.helix.app.ui

internal data class ComposerActions(
    val onFile: () -> Unit,
    val onPhoto: () -> Unit = {},
    val onCamera: () -> Unit = {},
    val onReference: () -> Unit = {},
    val onExpert: () -> Unit = {},
    val onSkills: () -> Unit = {},
    val onConnectors: () -> Unit = {},
    val onSessionSettings: () -> Unit = {},
    val onVoice: () -> Unit,
    val onSend: () -> Unit,
    val onStop: () -> Unit,
)
