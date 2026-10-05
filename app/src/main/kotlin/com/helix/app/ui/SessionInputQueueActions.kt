package com.helix.app.ui

import com.helix.core.storage.repository.SessionInputRecord

internal data class SessionInputQueueActions(
    val edit: (SessionInputRecord) -> Unit,
    val delete: (SessionInputRecord) -> Unit,
    val send: (SessionInputRecord, String?) -> Unit,
)
