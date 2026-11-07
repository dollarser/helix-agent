package com.helix.feature.conversation.presentation

import com.helix.core.model.ToolCallState
import com.helix.feature.conversation.R

/** Localized labels are injected into the pure projector at the presentation boundary. */
fun conversationScreenProjection(strings: (Int, Array<out Any>) -> String) =
    ChatScreenProjection(
        toolStateLabel = { state ->
            val resource =
                when (state) {
                    ToolCallState.PENDING -> R.string.tool_state_processing
                    ToolCallState.AWAITING_APPROVAL -> R.string.tool_state_awaiting_approval
                    ToolCallState.RUNNING -> R.string.tool_state_running
                    ToolCallState.NEEDS_REVIEW -> R.string.tool_state_needs_review
                    ToolCallState.INTERRUPTED -> R.string.tool_state_interrupted
                    ToolCallState.COMPLETED -> R.string.tool_state_completed
                    ToolCallState.FAILED -> R.string.tool_state_failed
                    ToolCallState.CANCELLED -> R.string.tool_state_cancelled
                    ToolCallState.DENIED -> R.string.tool_state_denied
                    null -> R.string.tool_state_unknown
                }
            strings(resource, emptyArray())
        },
        cancelledBeforeStart = { strings(R.string.tool_summary_cancelled_before_start, emptyArray()) },
        bindingIssueLabel = { strings(it.messageResource, emptyArray()) },
    )
