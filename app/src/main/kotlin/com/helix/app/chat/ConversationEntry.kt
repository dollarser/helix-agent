package com.helix.app.chat

/** Stable turn grouping; persisted messages keep their order inside each turn. */
internal data class ConversationEntry(
    val key: String,
    val messages: List<MessageUi>,
    val tools: List<ToolTimelineRow>,
    val recoveries: List<SubscriptionRecoveryUi>,
) {
    val timeline: List<ConversationContent>
        get() =
            (
                messages.mapIndexed { index, message ->
                    ConversationContent.Message(message, message.sequence ?: index.toLong())
                } + tools.map { ConversationContent.Tool(it) }
            ).sortedWith(
                compareBy<ConversationContent> { it.sequence }.thenBy { it.index },
            )

    fun forkMessageId(screen: ChatScreenState): String? {
        val turn = screen.turns.firstOrNull { it.id == key }
        val running =
            (turn != null && !turn.state.isTerminal) ||
                (screen.activeTurn?.id == key && screen.isSending)
        val candidate = messages.lastOrNull { it.role == "assistant" }
        val inheritedIntermediate =
            candidate?.turnId == null && candidate != null &&
                screen.messages.getOrNull(screen.messages.indexOf(candidate) + 1)?.role == "assistant"
        return candidate?.id?.takeUnless { running || inheritedIntermediate }
    }
}

internal fun conversationEntries(screen: ChatScreenState): List<ConversationEntry> {
    val keys = linkedSetOf<String>()
    // Inherited history has no live execution identity; it precedes newly executed Turns.
    screen.messages.takeWhile { it.turnId == null }.forEach { keys += "message-${it.id}" }
    screen.turns.forEach { keys += it.id }
    screen.messages.forEach { keys += it.turnId ?: "message-${it.id}" }
    screen.toolTimeline.forEach { keys += it.turnId }
    screen.subscriptionRecoveries.forEach { keys += it.turnId }
    val messages = screen.messages.groupBy { it.turnId ?: "message-${it.id}" }
    val tools = screen.toolTimeline.groupBy { it.turnId }
    val recoveries = screen.subscriptionRecoveries.groupBy { it.turnId }
    return keys.map { key ->
        ConversationEntry(
            key,
            messages[key].orEmpty(),
            tools[key].orEmpty(),
            recoveries[key].orEmpty(),
        )
    }
}

internal sealed interface ConversationContent {
    val sequence: Long
    val index: Int
    val key: String

    data class Message(
        val value: MessageUi,
        override val sequence: Long,
    ) : ConversationContent {
        override val index: Int get() = -1
        override val key: String get() = value.id
    }

    data class Tool(
        val value: ToolTimelineRow,
    ) : ConversationContent {
        override val sequence: Long get() = value.position?.sequence ?: Long.MAX_VALUE
        override val index: Int get() = value.position?.index ?: 0
        override val key: String get() = "tool-${value.callId}"
    }
}
