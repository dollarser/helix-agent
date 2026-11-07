package com.helix.feature.conversation.application

/** Availability and recovery of future session work. Existing execution bindings never move. */
interface ConversationWorkspace {
    fun availableDirectory(reference: String?): String?

    fun sharedDirectory(sessionId: String): String?

    fun recover(
        sessionId: String,
        now: Long,
    )

    fun record(
        sessionId: String,
        now: Long,
    )

    fun hasNotice(sessionId: String): Boolean
}
