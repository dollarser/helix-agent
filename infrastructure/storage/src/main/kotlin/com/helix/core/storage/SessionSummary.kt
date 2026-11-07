package com.helix.core.storage

/** Read-only session list projection; does not expose provider, model, directory or history. */
data class SessionSummary(
    val id: String,
    val title: String,
    val archived: Boolean,
)

/** Picker metadata; message bodies remain in ContentStore. */
data class ReferenceSessionSummary(
    @androidx.room.Embedded val session: com.helix.core.storage.entity.SessionEntity,
    val messageCount: Int,
)
