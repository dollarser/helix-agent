package com.helix.feature.conversation.application

import com.helix.core.agent.RunControlConfig

/** The current selectable session facts, excluding repository indexes and durable record metadata. */
data class ConversationSessionSelection(
    val id: String,
    val providerId: String?,
    val modelId: String?,
    val archived: Boolean,
)

/** Original authorized tool identity, revalidated by the caller before the atomic settings command. */
data class SessionSettingsAuthority(
    val turnId: String,
    val toolCallId: String,
)

/** Closed commands for one session metadata aggregate; no generic repository access crosses this boundary. */
@Suppress("TooManyFunctions")
interface SessionCatalog {
    fun selection(sessionId: String): ConversationSessionSelection?

    fun projectId(sessionId: String): String?

    fun nameUntitled(
        sessionId: String,
        title: String,
    )

    fun persistDraft(draft: SessionDraft)

    fun projectSelection(
        id: String?,
        validateDirectory: Boolean = true,
    ): DraftProject?

    fun isNewConversation(id: String): Boolean

    fun selectProject(
        id: String,
        selection: DraftProject?,
    )

    fun rename(
        id: String,
        title: String,
    )

    fun create(
        title: String,
        providerId: String?,
        modelId: String?,
    ): String

    suspend fun fork(
        sessionId: String,
        messageId: String,
        branchTitle: (String) -> String,
    ): String

    fun setArchived(
        id: String,
        archived: Boolean,
    )

    fun bindProvider(
        sessionId: String,
        providerId: String,
        modelId: String,
    )

    fun setDirectory(
        id: String,
        bound: String?,
    ): Boolean

    fun persistSettings(
        sessionId: String,
        providerId: String,
        modelId: String,
        next: RunControlConfig,
        call: SessionSettingsAuthority?,
    ): Boolean

    /** Validates an explicit project binding or prepares a private future-work workspace. */
    fun ensureWorkspace(sessionId: String)
}

interface ConversationRunControls {
    fun defaultSnapshot(): RunControlConfig

    fun forSession(sessionId: String): RunControlConfig?

    fun ensure(
        sessionId: String,
        nowEpochMillis: Long,
    ): RunControlConfig

    fun set(
        sessionId: String,
        config: RunControlConfig,
        nowEpochMillis: Long,
    ): Long
}
