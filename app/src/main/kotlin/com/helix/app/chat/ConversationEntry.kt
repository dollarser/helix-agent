package com.helix.app.chat

/** Stable turn grouping; persisted messages keep their order inside each turn. */
internal data class ConversationEntry(
    val key: String,
    val messages: List<MessageUi>,
    val tools: List<ToolTimelineRow>,
    val recoveries: List<SubscriptionRecoveryUi>,
)

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
