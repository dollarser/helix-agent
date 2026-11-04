package com.helix.app.chat

/** User-selected project identity and revision; directory remains a separate resource binding. */
internal data class DraftProject(
    val id: String,
    val revision: Long,
    val directory: String,
)
