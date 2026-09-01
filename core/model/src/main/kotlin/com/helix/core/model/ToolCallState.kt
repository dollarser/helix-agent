package com.helix.core.model

/**
 * ToolCall state (architecture doc sections 5.2/7.1, security doc section 7.1).
 *
 * ```text
 * PENDING -> AWAITING_APPROVAL | RUNNING | DENIED | FAILED | CANCELLED | NEEDS_REVIEW
 * AWAITING_APPROVAL -> RUNNING | DENIED | CANCELLED | FAILED
 * RUNNING -> COMPLETED | FAILED | CANCELLED | NEEDS_REVIEW
 * process death: PENDING | RUNNING -> INTERRUPTED
 * ```
 *
 * [DENIED] covers policy denials and user rejections: a denial is a legal [com.helix.core.model]
 * ToolResult the agent may react to, but the call never executed, so it must stay distinguishable
 * from [COMPLETED] in audit and recovery. [NEEDS_REVIEW] parks a call whose external side
 * effects are unclear; it is never retried automatically (security doc section 7.1).
 * [INTERRUPTED] is durable: [AWAITING_APPROVAL] is already persisted, so process death while
 * waiting for approval does not change the state.
 */
enum class ToolCallState(
    val isTerminal: Boolean,
) {
    PENDING(false),
    AWAITING_APPROVAL(false),
    RUNNING(false),
    NEEDS_REVIEW(false),
    INTERRUPTED(false),
    COMPLETED(true),
    FAILED(true),
    CANCELLED(true),
    DENIED(true),
    ;

    /** Parked states wait for an explicit user/recovery decision; nothing auto-transitions them. */
    val isParked: Boolean
        get() = this == NEEDS_REVIEW || this == INTERRUPTED

    fun canTransitionTo(next: ToolCallState): Boolean {
        if (isTerminal || isParked) return false
        return next in outgoing
    }

    /** Process death only parks in-flight calls; durable states keep their value. */
    fun canBecomeInterruptedOnProcessDeath(): Boolean = this == PENDING || this == RUNNING

    private val outgoing: Set<ToolCallState>
        get() =
            when (this) {
                // NEEDS_REVIEW covers a dispatcher contract throw after an executor may
                // have started but before the application received a typed settlement.
                PENDING -> setOf(AWAITING_APPROVAL, RUNNING, DENIED, FAILED, CANCELLED, NEEDS_REVIEW)

                AWAITING_APPROVAL -> setOf(RUNNING, DENIED, CANCELLED, FAILED)

                RUNNING -> setOf(COMPLETED, FAILED, CANCELLED, NEEDS_REVIEW)

                else -> emptySet()
            }

    companion object {
        val TERMINAL: Set<ToolCallState> = setOf(COMPLETED, FAILED, CANCELLED, DENIED)
    }
}
