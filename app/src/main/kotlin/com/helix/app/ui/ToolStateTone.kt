package com.helix.app.ui

import com.helix.app.R
import com.helix.core.model.ToolCallState

internal enum class ToolStateTone { NEUTRAL, ACTIVE, COMPLETE, ERROR, REVIEW }

/** Execution success is not a business-success assertion. Never interprets a translated label. */
internal fun toolStateTone(state: ToolCallState): ToolStateTone =
    when (state) {
        ToolCallState.PENDING, ToolCallState.CANCELLED -> ToolStateTone.NEUTRAL
        ToolCallState.AWAITING_APPROVAL, ToolCallState.RUNNING -> ToolStateTone.ACTIVE
        ToolCallState.COMPLETED -> ToolStateTone.COMPLETE
        ToolCallState.FAILED, ToolCallState.DENIED -> ToolStateTone.ERROR
        ToolCallState.NEEDS_REVIEW, ToolCallState.INTERRUPTED -> ToolStateTone.REVIEW
    }

internal fun toolStateIcon(state: ToolCallState): Int =
    when (toolStateTone(state)) {
        ToolStateTone.COMPLETE -> {
            R.drawable.ic_check
        }

        ToolStateTone.ERROR -> {
            R.drawable.ic_recovery_warning
        }

        ToolStateTone.REVIEW -> {
            R.drawable.ic_status_unknown
        }

        ToolStateTone.ACTIVE -> {
            R.drawable.ic_drawer_tasks
        }

        ToolStateTone.NEUTRAL -> {
            if (state == ToolCallState.CANCELLED) {
                R.drawable.ic_close
            } else {
                R.drawable.ic_drawer_tasks
            }
        }
    }
