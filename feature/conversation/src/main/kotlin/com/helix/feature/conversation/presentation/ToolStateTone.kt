package com.helix.feature.conversation.presentation

import com.helix.core.model.ToolCallState
import com.helix.feature.conversation.R
import com.helix.ui.shared.R as SharedR

enum class ToolStateTone { NEUTRAL, ACTIVE, COMPLETE, ERROR, REVIEW }

/** Execution success is not a business-success assertion. Never interprets a translated label. */
fun toolStateTone(state: ToolCallState): ToolStateTone =
    when (state) {
        ToolCallState.PENDING, ToolCallState.CANCELLED -> ToolStateTone.NEUTRAL
        ToolCallState.AWAITING_APPROVAL, ToolCallState.RUNNING -> ToolStateTone.ACTIVE
        ToolCallState.COMPLETED -> ToolStateTone.COMPLETE
        ToolCallState.FAILED, ToolCallState.DENIED -> ToolStateTone.ERROR
        ToolCallState.NEEDS_REVIEW, ToolCallState.INTERRUPTED -> ToolStateTone.REVIEW
    }

fun toolStateIcon(state: ToolCallState): Int =
    when (toolStateTone(state)) {
        ToolStateTone.COMPLETE -> {
            SharedR.drawable.ic_check
        }

        ToolStateTone.ERROR -> {
            R.drawable.ic_recovery_warning
        }

        ToolStateTone.REVIEW -> {
            R.drawable.ic_status_unknown
        }

        ToolStateTone.ACTIVE -> {
            SharedR.drawable.ic_drawer_tasks
        }

        ToolStateTone.NEUTRAL -> {
            if (state == ToolCallState.CANCELLED) {
                SharedR.drawable.ic_close
            } else {
                SharedR.drawable.ic_drawer_tasks
            }
        }
    }
