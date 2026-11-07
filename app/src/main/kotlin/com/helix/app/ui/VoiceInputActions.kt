package com.helix.app.ui

/** Composer-owned draft actions; capture and account routing are supplied by the application boundary. */
internal data class VoiceInputActions(
    val onDismiss: () -> Unit,
    val onAccept: (String) -> Unit,
    val onPreview: (String) -> Unit,
    val onError: (String) -> Unit,
)
