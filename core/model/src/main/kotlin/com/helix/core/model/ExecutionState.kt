package com.helix.core.model

/**
 * Code/command execution state, tracked in the execution layer (QuickJS/PRoot/CLI flows,
 * local execution doc section 4) until HXA-035+ wires an executor. The persisted `executions`
 * row (architecture doc section 9.1) records the final outcome via `exitCode`/`signal` only —
 * it has no state column — so this machine is the in-flight contract, not the schema.
 *
 * ```text
 * PENDING -> RUNNING | CANCELLED | FAILED
 * RUNNING -> COMPLETED | FAILED | CANCELLED | TIMED_OUT
 * process death: PENDING | RUNNING -> INTERRUPTED
 * ```
 *
 * [TIMED_OUT] is the stable outcome of the interrupt-then-cancel flow (timeout triggers
 * interrupt; if nothing returns within the 1 s grace period the main process cancels the
 * Binder interaction — local execution doc section 4). An [INTERRUPTED] execution has unclear
 * side effects for mutating runtimes and is parked for review, never replayed.
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
