package com.helix.app.ui

/** Read-only conversation projection shown by the global drawer; it owns no session state. */
internal data class ConversationDrawerState(
    val currentSessionId: String?,
    val currentTitle: String,
)
