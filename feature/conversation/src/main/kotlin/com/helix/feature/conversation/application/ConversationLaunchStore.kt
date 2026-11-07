package com.helix.feature.conversation.application

sealed interface ConversationLaunchTarget {
    data object NewDraft : ConversationLaunchTarget

    data class Session(
        val sessionId: String,
    ) : ConversationLaunchTarget
}

interface ConversationLaunchStore {
    fun draftModel(): Pair<String, String>?

    fun draftModelFor(current: Pair<String?, String?>?): Pair<String?, String?>

    fun selectDraftModel(
        providerId: String?,
        modelId: String?,
    )

    fun target(): ConversationLaunchTarget

    fun selectNewDraft()

    fun selectSession(sessionId: String)
}
