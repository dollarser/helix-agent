package com.helix.core.model

/**
 * Persistent Goal state machine (modes doc section 6.2).
 *
 * ```text
 * DRAFT          -> READY | CANCELLED
 * READY          -> RUNNING | CANCELLED
 * RUNNING        -> INPUT_REQUIRED | PAUSED | BLOCKED | COMPLETED | FAILED | CANCELLED
 * INPUT_REQUIRED -> RUNNING | CANCELLED   (explicit user resume / discard)
 * PAUSED         -> RUNNING | BLOCKED | CANCELLED (explicit continue / dependency / discard)
 * BLOCKED        -> PAUSED | CANCELLED (host recheck after repair / discard)
 * process death: RUNNING -> PAUSED        (durable park; resume is user-explicit)
 * ```
 *
 * Goals never share the Turn state table (architecture doc section 5.2). Only explicit user
 * action creates a new `goal_run`; WorkManager reminders may be delayed or dropped by Doze and
 * force-stop and never start model or tool work. [COMPLETED] consumes a model report after
 * host execution gates; budget exhaustion lands in [BLOCKED], never in
 * [COMPLETED].
 */
enum class GoalState(
    val isTerminal: Boolean,
) {
    DRAFT(false),
    READY(false),
    RUNNING(false),
    INPUT_REQUIRED(false),
    PAUSED(false),
    BLOCKED(false),
    COMPLETED(true),
    FAILED(true),
    CANCELLED(true),
    ;

    fun canTransitionTo(next: GoalState): Boolean {
        if (isTerminal) return false
        return next in outgoing
    }

    /**
     * Process death parks an in-flight [RUNNING] goal in [PAUSED]: the active Turn is marked
     * [TurnState.INTERRUPTED] by the recovery coordinator and only an explicit user continue
     * creates a new run. All other goal states are durable and unchanged by process death.
     */
    fun stateAfterProcessDeath(): GoalState = if (this == RUNNING) PAUSED else this

    private val outgoing: Set<GoalState>
        get() =
            when (this) {
                DRAFT -> setOf(READY, CANCELLED)
                READY -> setOf(RUNNING, CANCELLED)
                RUNNING -> setOf(INPUT_REQUIRED, PAUSED, BLOCKED, COMPLETED, FAILED, CANCELLED)
                INPUT_REQUIRED -> setOf(RUNNING, CANCELLED)
                PAUSED -> setOf(RUNNING, BLOCKED, CANCELLED)
                BLOCKED -> setOf(PAUSED, CANCELLED)
                COMPLETED, FAILED, CANCELLED -> emptySet()
            }

    companion object {
        val TERMINAL: Set<GoalState> = setOf(COMPLETED, FAILED, CANCELLED)
    }
}
