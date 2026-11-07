package com.helix.feature.conversation.presentation

/** Composer-owned draft actions; capture and account routing are supplied by the application boundary. */
data class VoiceInputActions(
    val onDismiss: () -> Unit,
    val onAccept: (String) -> Unit,
    val onPreview: (String) -> Unit,
    val onError: (String) -> Unit,
)
