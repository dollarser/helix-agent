package com.helix.app.agent

import com.helix.core.agent.runtime.GoalDeadlineControl

/** Host-only atomic Room lease handoff on the original clock, never a second timer registry. */
internal interface GoalLeaseControl : GoalDeadlineControl {
    fun transferToLease(
        id: String,
        requestedMillis: Long,
        persist: (GoalLeaseAllocation) -> Unit = {},
    ): GoalLeaseAllocation?

    fun completeLease(allocation: GoalLeaseAllocation): Boolean
}
