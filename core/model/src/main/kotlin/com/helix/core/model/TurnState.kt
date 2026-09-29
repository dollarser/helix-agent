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
 *                    -> RUNNING_TOOL
 *                    -> COMPLETED
 *                    -> FAILED
 *              -> FAILED
 *
 * RECORDING_TOOL_RESULT -> BUILDING_CONTEXT -> WAITING_MODEL   (loop after a settled batch)
 *
 * A model response's tool calls are a batch: individual PENDING/AWAITING_APPROVAL/RUNNING/
 * terminal states live on ToolCall rows, while the Turn uses RUNNING_TOOL as the aggregate
 * batch phase. Safe reads may execute concurrently; every result is persisted in original
 * call sequence before BUILDING_CONTEXT. Approval waiting is therefore a ToolCall fact only.
 *
 * any non-terminal state -> CANCELLING -> CANCELLED
 * live UNKNOWN: RUNNING_TOOL/CANCELLING -> NEEDS_REVIEW
 * process death on an advancing non-terminal state -> INTERRUPTED
 * NEEDS_REVIEW -> INTERRUPTED (review resolved or automatic inspection ended; effect facts retained)
 *              | CANCELLED (explicit abandon)
 * INTERRUPTED is execution-terminal; continuation always creates a successor Turn.
 * ```
 *
 * Terminal states are [COMPLETED], [FAILED], [CANCELLED] and [INTERRUPTED]. NEEDS_REVIEW is
 * execution-stopped but remains non-terminal until review or automatic inspection ends the attempt.
 * Ending an attempt never proves its unknown external effects were settled.
 */
enum class TurnState(
    val isTerminal: Boolean,
) {
    CREATED(false),
    BUILDING_CONTEXT(false),
    WAITING_MODEL(false),
    RECEIVING_MODEL(false),
    RUNNING_TOOL(false),
    RECORDING_TOOL_RESULT(false),
    CANCELLING(false),
    NEEDS_REVIEW(false),
    INTERRUPTED(true),
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
            next == CANCELLING -> this != CANCELLING && this !in setOf(NEEDS_REVIEW, INTERRUPTED)
            else -> next in outgoing
        }

    /**
     * Process death (crash, kill, power loss) moves any non-terminal state to [INTERRUPTED].
     * A turn already [INTERRUPTED] stays [INTERRUPTED]; terminal states never change.
     */
    fun canBecomeInterruptedOnProcessDeath(): Boolean = !isTerminal && this !in setOf(NEEDS_REVIEW, INTERRUPTED)

    private val outgoing: Set<TurnState>
        get() =
            when (this) {
                CREATED -> setOf(BUILDING_CONTEXT)

                // FAILED: the pre-call budget gate can exhaust the turn before any model
                // stream starts (doc 02 section 5.3: budget is computed before each call).
                BUILDING_CONTEXT -> setOf(WAITING_MODEL, FAILED)

                WAITING_MODEL -> setOf(RECEIVING_MODEL, FAILED)

                RECEIVING_MODEL -> setOf(BUILDING_CONTEXT, RUNNING_TOOL, COMPLETED, FAILED)

                RUNNING_TOOL -> setOf(RECORDING_TOOL_RESULT, NEEDS_REVIEW, FAILED)

                RECORDING_TOOL_RESULT -> setOf(BUILDING_CONTEXT, RUNNING_TOOL, FAILED)

                CANCELLING -> setOf(NEEDS_REVIEW, CANCELLED)

                NEEDS_REVIEW -> setOf(INTERRUPTED, CANCELLED)

                INTERRUPTED -> emptySet()

                COMPLETED, FAILED, CANCELLED -> emptySet()
            }

    companion object {
        val TERMINAL: Set<TurnState> = setOf(INTERRUPTED, COMPLETED, FAILED, CANCELLED)
    }
}
