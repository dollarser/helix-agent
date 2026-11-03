package com.helix.core.storage.entity

/** Read projection only: dashboard facts without loading tool argument/result payloads. */
data class BackgroundTurnMetadata(
    val turnId: String,
    val title: String,
    val runId: String?,
    val goalId: String?,
    val goalOutcome: String?,
    val awaitingApproval: Boolean,
)
