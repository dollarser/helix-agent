package com.helix.core.storage

/** Read-only session list projection; does not expose provider, model, directory or history. */
data class SessionSummary(
    val id: String,
    val title: String,
    val archived: Boolean,
)
