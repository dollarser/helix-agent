package com.helix.feature.conversation.presentation

import com.helix.core.model.ToolCallState
import com.helix.feature.conversation.presentation.ApprovalCardState

/** Collapse adjacent settled calls within a Turn; visible messages and attention states remain boundaries. */
fun groupCompletedTools(contents: List<ConversationContent>): List<ConversationContent> {
    val result = mutableListOf<ConversationContent>()
    val pending = mutableListOf<ConversationContent.Tool>()

    fun flush() {
        if (pending.size > 1) {
            result += ConversationContent.ToolGroup(pending.map { it.value })
        } else {
            result += pending
        }
        pending.clear()
    }
    for (content in contents) {
        val tool = content as? ConversationContent.Tool
        val row = tool?.value
        val eligible =
            row?.state == ToolCallState.COMPLETED && row.position != null &&
                row.card?.state != ApprovalCardState.PENDING && !toolHasUnsettledObservation(row)
        if (eligible) {
            val previous = pending.lastOrNull()?.value
            if (previous != null && previous.turnId != row.turnId) {
                flush()
            }
            pending += requireNotNull(tool)
        } else {
            flush()
            result += content
        }
    }
    flush()
    return result
}
