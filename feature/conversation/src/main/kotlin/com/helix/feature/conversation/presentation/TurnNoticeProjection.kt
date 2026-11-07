package com.helix.feature.conversation.presentation

import com.helix.core.agent.TurnInterruptionNotice

/** A late recovery fact stays at its original message, even as newer turns are appended. */
fun attachTurnNotices(
    messages: List<MessageUi>,
    notices: Map<String, TurnInterruptionNotice>,
): List<MessageUi> {
    val anchors =
        messages.filter { it.turnId in notices }.groupBy { it.turnId }.mapValues { (_, rows) ->
            (rows.lastOrNull { it.role == "assistant" } ?: rows.lastOrNull { it.role == "user" })?.id
        }
    return messages.map { row ->
        row.copy(interruptionNotice = notices[row.turnId]?.takeIf { anchors[row.turnId] == row.id })
    }
}
