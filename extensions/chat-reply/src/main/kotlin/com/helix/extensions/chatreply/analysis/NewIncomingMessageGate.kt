package com.helix.extensions.chatreply.analysis

/** Ignores upward history scrolling and repeated visible messages. */
class NewIncomingMessageGate(
    private val maxRemembered: Int = 500,
) {
    enum class Result {
        FIRST_OBSERVATION,
        NEW_INCOMING,
        NEW_OUTGOING,
        NO_NEW_MESSAGE,
    }

    private val seen = LinkedHashSet<String>()

    init {
        require(maxRemembered > 0)
    }

    fun reset() = seen.clear()

    fun observe(messages: List<ChatMessage>): Result {
        if (messages.isEmpty()) return Result.NO_NEW_MESSAGE
        val first = seen.isEmpty()
        val keys = messages.map { "${it.side.name}\u0000${it.text}" }
        val lastPreviouslySeen = keys.indexOfLast { it in seen }
        keys.forEach { key ->
            seen.remove(key)
            seen.add(key)
        }
        while (seen.size > maxRemembered) seen.remove(seen.first())
        return when {
            first -> Result.FIRST_OBSERVATION
            lastPreviouslySeen < 0 || lastPreviouslySeen == keys.lastIndex -> Result.NO_NEW_MESSAGE
            messages.last().side == MessageSide.OTHER -> Result.NEW_INCOMING
            else -> Result.NEW_OUTGOING
        }
    }
}
