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
    screen.turns.forEach { keys += it.id }
    screen.messages.forEach { keys += it.turnId ?: "message-${it.id}" }
    screen.toolTimeline.forEach { keys += it.turnId }
    screen.subscriptionRecoveries.forEach { keys += it.turnId }
    return keys.map { key ->
        ConversationEntry(
            key,
            screen.messages.filter { (it.turnId ?: "message-${it.id}") == key },
            screen.toolTimeline.filter { it.turnId == key },
            screen.subscriptionRecoveries.filter { it.turnId == key },
        )
    }
}
