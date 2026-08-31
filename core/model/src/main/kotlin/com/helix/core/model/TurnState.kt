package com.helix.core.model

/**
 * Agent Turn state machine (architecture doc section 5.2).
 *
 * ```text
 * CREATED
 *   -> BUILDING_CONTEXT
 *        -> WAITING_MODEL
 *        -> FAILED                    (pre-call budget gate exhausted)
 *              -> RECEIVING_MODEL
 *                    -> WAITING_APPROVAL
 *                    |      -> RUNNING_TOOL          (approved)
 *                    |      -> RECORDING_TOOL_RESULT (rejected)
 *                    -> RUNNING_TOOL
 *                    -> COMPLETED
 *                    -> FAILED
 *              -> FAILED
 *
 * RECORDING_TOOL_RESULT -> BUILDING_CONTEXT -> WAITING_MODEL   (loop after the last call)
 * RECORDING_TOOL_RESULT -> WAITING_APPROVAL | RUNNING_TOOL
 *        (serial next call from the same model response; first version executes all tool
 *        calls of one response serially - architecture doc 5.3 - and provider protocols
 *        require every call of a response to receive a result before the next model call)
 *
 * any non-terminal state -> CANCELLING -> CANCELLED
 * process death on any non-terminal state -> INTERRUPTED
 * INTERRUPTED -> BUILDING_CONTEXT (resume, after side-effect review) | CANCELLED (discard)
 * ```
 *
 * Terminal states are [COMPLETED], [FAILED] and [CANCELLED]. [INTERRUPTED] is recoverable but
 * the recovery coordinator (HXA-015) must check for ToolCalls with possibly unknown external
 * side effects before resuming; resuming here only encodes the state-space, not that policy.
 */
enum class TurnState(
    val isTerminal: Boolean,
) {
    CREATED(false),
    BUILDING_CONTEXT(false),
    WAITING_MODEL(false),
    RECEIVING_MODEL(false),
    WAITING_APPROVAL(false),
    RUNNING_TOOL(false),
    RECORDING_TOOL_RESULT(false),
    CANCELLING(false),
    INTERRUPTED(false),
    COMPLETED(true),
    FAILED(true),
    CANCELLED(true),
    ;

    /**
     * In-process transition validity. Cancellation may be requested from any non-terminal
     * state except [CANCELLING] itself and [INTERRUPTED] (a recovered turn is discarded
     * directly to [CANCELLED] because no live loop exists to cancel).
     */
    fun canTransitionTo(next: TurnState): Boolean =
        when {
            isTerminal -> false
            next == CANCELLING -> this != CANCELLING && this != INTERRUPTED
            else -> next in outgoing
        }

    /**
     * Process death (crash, kill, power loss) moves any non-terminal state to [INTERRUPTED].
     * A turn already [INTERRUPTED] stays [INTERRUPTED]; terminal states never change.
     */
    fun canBecomeInterruptedOnProcessDeath(): Boolean = !isTerminal && this != INTERRUPTED

    private val outgoing: Set<TurnState>
        get() =
            when (this) {
                CREATED -> setOf(BUILDING_CONTEXT)

                // FAILED: the pre-call budget gate can exhaust the turn before any model
                // stream starts (doc 02 section 5.3: budget is computed before each call).
                BUILDING_CONTEXT -> setOf(WAITING_MODEL, FAILED)

                WAITING_MODEL -> setOf(RECEIVING_MODEL, FAILED)

                RECEIVING_MODEL -> setOf(WAITING_APPROVAL, RUNNING_TOOL, COMPLETED, FAILED)

                WAITING_APPROVAL -> setOf(RUNNING_TOOL, RECORDING_TOOL_RESULT)

                RUNNING_TOOL -> setOf(RECORDING_TOOL_RESULT)

                RECORDING_TOOL_RESULT -> setOf(BUILDING_CONTEXT, WAITING_APPROVAL, RUNNING_TOOL)

                CANCELLING -> setOf(CANCELLED)

                INTERRUPTED -> setOf(BUILDING_CONTEXT, CANCELLED)

                COMPLETED, FAILED, CANCELLED -> emptySet()
            }

    companion object {
        val TERMINAL: Set<TurnState> = setOf(COMPLETED, FAILED, CANCELLED)
    }
}
