package com.helix.core.model

/**
 * Code/command execution state for the `executions` table (architecture doc section 9.1) and
 * the QuickJS/PRoot execution flows (local execution doc section 10).
 *
 * ```text
 * PENDING -> RUNNING | CANCELLED | FAILED
 * RUNNING -> COMPLETED | FAILED | CANCELLED | TIMED_OUT
 * process death: PENDING | RUNNING -> INTERRUPTED
 * ```
 *
 * [TIMED_OUT] is the stable outcome of the interrupt-then-cancel flow (interrupt, 1 s grace,
 * cancel the Binder interaction, recycle the isolated instance). An [INTERRUPTED] execution has
 * unclear side effects for mutating runtimes and is parked for review, never replayed.
 */
enum class ExecutionState(
    val isTerminal: Boolean,
) {
    PENDING(false),
    RUNNING(false),
    INTERRUPTED(false),
    COMPLETED(true),
    FAILED(true),
    CANCELLED(true),
    TIMED_OUT(true),
    ;

    val isParked: Boolean
        get() = this == INTERRUPTED

    fun canTransitionTo(next: ExecutionState): Boolean {
        if (isTerminal || isParked) return false
        return next in outgoing
    }

    fun canBecomeInterruptedOnProcessDeath(): Boolean = this == PENDING || this == RUNNING

    private val outgoing: Set<ExecutionState>
        get() =
            when (this) {
                PENDING -> setOf(RUNNING, CANCELLED, FAILED)
                RUNNING -> setOf(COMPLETED, FAILED, CANCELLED, TIMED_OUT)
                else -> emptySet()
            }

    companion object {
        val TERMINAL: Set<ExecutionState> = setOf(COMPLETED, FAILED, CANCELLED, TIMED_OUT)
    }
}
