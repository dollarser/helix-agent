package com.helix.core.storage.input

/** Replaceable per-session file-cache value, not a Room entity or an accepted-message record. */
data class ComposerInputSnapshot(
    val sessionId: String,
    val revision: Long,
    val clientRequestId: String,
    val text: String,
    val attachmentIdsJson: String,
    val revisedMessageId: String? = null,
    val delivery: String = "QUEUE",
    val expectedTurnId: String? = null,
    val referenceSourceSessionId: String? = null,
    val referenceKind: String? = null,
)
