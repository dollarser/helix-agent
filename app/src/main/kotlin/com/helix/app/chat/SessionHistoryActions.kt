package com.helix.app.chat

/** User actions retain the facade's original ordering, permissions and durable gates. */
data class SessionHistoryActions(
    val archive: (String) -> Unit,
    val restore: (String) -> Unit,
    val rename: (String, String) -> Unit,
)
