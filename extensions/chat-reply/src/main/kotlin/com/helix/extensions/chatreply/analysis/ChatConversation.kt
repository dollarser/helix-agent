package com.helix.extensions.chatreply.analysis

/** App-neutral conversation assembled only from a fresh authorized UI snapshot. */
data class ChatConversation(
    val packageName: String,
    val windowId: Int,
    val title: String?,
    val messages: List<ChatMessage>,
) {
    init {
        require(packageName.isNotBlank())
        require(windowId >= 0)
        require(messages.size <= MAX_MESSAGES)
        require(messages.all { it.text.isNotBlank() && it.text.length <= MAX_MESSAGE_CHARS })
    }

    val latestIncoming: ChatMessage?
        get() = messages.lastOrNull { it.side == MessageSide.OTHER }

    fun sameConversation(other: ChatConversation): Boolean =
        packageName == other.packageName && windowId == other.windowId && title == other.title

    fun signature(): String = messages.joinToString("\u0001") { "${it.side.name}\u0000${it.text}" }

    companion object {
        const val MAX_MESSAGES = 80
        const val MAX_MESSAGE_CHARS = 4_000
    }
}

data class ChatMessage(
    val side: MessageSide,
    val text: String,
)

enum class MessageSide {
    ME,
    OTHER,
}
