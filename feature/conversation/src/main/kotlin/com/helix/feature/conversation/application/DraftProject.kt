package com.helix.feature.conversation.application

/** User-selected project identity and revision; directory remains a separate resource binding. */
data class DraftProject(
    val id: String,
    val revision: Long,
    val directory: String,
)
