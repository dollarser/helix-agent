package com.helix.core.agent

import com.helix.core.model.GoalId
import com.helix.core.model.GoalState
import com.helix.core.model.ToolCallId
import com.helix.core.model.ToolCallState
import com.helix.core.model.TurnId
import com.helix.core.model.TurnState

/**
 * A tool call row as it was persisted by the previous (now dead) process (doc 9.1
 * `tool_calls` rows grouped under their turn).
 */
data class PersistedToolCall(
    val callId: ToolCallId,
    val state: ToolCallState,
)

/**
 * A turn as it was persisted by the previous (now dead) process: the `turns` row phase plus
 * its `tool_calls` rows. The in-memory [TurnState] of the dead process is gone, so recovery
 * decisions must be derivable from these rows alone.
 */
data class PersistedTurn(
    val turnId: TurnId,
    val phase: TurnState,
    val toolCalls: List<PersistedToolCall>,
) {
    init {
        val ids = toolCalls.map { it.callId }
        require(ids.distinct().size == ids.size) { "tool call ids must be unique within a turn" }
        // Serial execution (first version, doc 02 section 5.3): at most one call is RUNNING
        // at any time, so a turn that died mid-execution has at most one uncertain call.
        require(toolCalls.count { it.state == ToolCallState.RUNNING } <= 1) {
            "at most one RUNNING tool call per turn (serial execution)"
        }
    }

    /** The call that was executing when the process died — its external effect is unclear. */
    val runningCallId: ToolCallId?
        get() = toolCalls.firstOrNull { it.state == ToolCallState.RUNNING }?.callId
}

/** A goal as it was persisted by the previous process (doc 9.1 `goals` row). */
data class PersistedGoal(
    val goalId: GoalId,
    val state: GoalState,
)

/** Decision for one persisted turn. */
sealed interface TurnRecovery {
    /** Terminal or already interrupted: recovery is a no-op for this turn (idempotent). */
    data object NoAction : TurnRecovery

    /** Mark the turn INTERRUPTED; [uncertainToolCall] (if any) needs side-effect review first. */
    data class Interrupt(
        val turnId: TurnId,
        val uncertainToolCall: ToolCallId?,
    ) : TurnRecovery
}

/** Decision for one persisted tool call. */
sealed interface ToolCallRecovery {
    /** Durable state (AWAITING_APPROVAL, NEEDS_REVIEW, INTERRUPTED, terminal): unchanged. */
    data object Keep : ToolCallRecovery

    /** The call was in flight (PENDING/RUNNING) at death: park in INTERRUPTED, never replay. */
    data object ParkInterrupted : ToolCallRecovery
}

/** Decision for one persisted goal. */
sealed interface GoalRecovery {
    /** Durable state: unchanged by process death. */
    data object NoAction : GoalRecovery

    /** A RUNNING goal parks in PAUSED ([GoalState.stateAfterProcessDeath]). */
    data class Park(
        val goalId: GoalId,
    ) : GoalRecovery
}

/** One parked tool call (turn-scoped for the audit trail). */
data class ToolCallParking(
    val turnId: TurnId,
    val toolCallId: ToolCallId,
)

/**
 * The complete, deterministic recovery plan for one process restart. The plan only marks and
 * parks — it contains no re-execution of any kind (roadmap HXA-015: never auto-replay
 * side-effectful or unclear ToolCalls).
 */
data class RecoveryPlan(
    val interruptedTurns: List<TurnRecovery.Interrupt>,
    val parkedToolCalls: List<ToolCallParking>,
    val parkedGoals: List<GoalRecovery.Park>,
) {
    val isEmpty: Boolean
        get() = interruptedTurns.isEmpty() && parkedToolCalls.isEmpty() && parkedGoals.isEmpty()
}

/**
 * Process-death recovery coordinator (HXA-015). Pure decision layer over persisted facts
 * (doc 02 section 5.2, doc 07 section 7.1, ADR-0004):
 *
 * - any non-terminal turn that is not already INTERRUPTED becomes INTERRUPTED; the turn's
 *   RUNNING call (if any) is the uncertain side effect;
 * - only in-flight calls (PENDING/RUNNING) are parked in INTERRUPTED
 *   ([ToolCallState.canBecomeInterruptedOnProcessDeath]); a call still in AWAITING_APPROVAL
 *   never executed and is not uncertain; NEEDS_REVIEW/INTERRUPTED are durable parked states;
 * - a RUNNING goal parks in PAUSED; every other goal state is durable;
 * - resuming an interrupted turn requires the uncertain call (if any) to be resolved first
 *   (the [TurnReducer] `TurnResumed` gate enforces the same rule on the runtime state);
 * - a wake (USER_OPEN/NOTIFICATION_ACTION) is only accepted from READY/PAUSED/INPUT_REQUIRED
 *   (the [GoalReducer] `Continued` gate), so a stale wake against a RUNNING or terminal goal
 *   is dropped.
 */
object RecoveryCoordinator {
    fun recoveryForTurn(turn: PersistedTurn): TurnRecovery =
        when {
            turn.phase.isTerminal -> TurnRecovery.NoAction
            turn.phase == TurnState.INTERRUPTED -> TurnRecovery.NoAction
            else -> TurnRecovery.Interrupt(turn.turnId, turn.runningCallId)
        }

    fun recoveryForToolCall(call: PersistedToolCall): ToolCallRecovery =
        if (call.state.canBecomeInterruptedOnProcessDeath()) {
            ToolCallRecovery.ParkInterrupted
        } else {
            ToolCallRecovery.Keep
        }

    fun recoveryForGoal(goal: PersistedGoal): GoalRecovery =
        if (goal.state.stateAfterProcessDeath() != goal.state) {
            GoalRecovery.Park(goal.goalId)
        } else {
            GoalRecovery.NoAction
        }

    /** Deterministic plan (sorted by id) for all persisted turns and goals of the app. */
    fun plan(
        turns: List<PersistedTurn>,
        goals: List<PersistedGoal>,
    ): RecoveryPlan {
        val interruptedTurns =
            turns
                .mapNotNull { turn -> recoveryForTurn(turn) as? TurnRecovery.Interrupt }
                .sortedBy { it.turnId.value }
        // Only calls under non-terminal turns are parked: a terminal turn (COMPLETED/FAILED/
        // CANCELLED) never had in-flight work — its queued calls were recorded Cancelled by
        // the reducer at cancel/discard time, and parking stale rows would fabricate an
        // "uncertain side effect" that does not exist.
        val parkedToolCalls =
            turns
                .filter { turn -> !turn.phase.isTerminal }
                .flatMap { turn ->
                    turn.toolCalls
                        .filter { call -> recoveryForToolCall(call) == ToolCallRecovery.ParkInterrupted }
                        .map { call -> ToolCallParking(turn.turnId, call.callId) }
                }.sortedWith(compareBy({ it.turnId.value }, { it.toolCallId.value }))
        val parkedGoals =
            goals
                .mapNotNull { goal -> recoveryForGoal(goal) as? GoalRecovery.Park }
                .sortedBy { it.goalId.value }
        return RecoveryPlan(interruptedTurns, parkedToolCalls, parkedGoals)
    }

    /**
     * Resume gate for an interrupted turn: only an INTERRUPTED turn whose uncertain call (if
     * any) has been resolved may be resumed. Mirrors the [TurnReducer] `TurnResumed` gate so
     * UI/audit can answer "may the user continue?" without reconstructing runtime state.
     */
    fun canResumeTurn(
        phase: TurnState,
        hasUncertainToolCall: Boolean,
    ): Boolean = phase == TurnState.INTERRUPTED && !hasUncertainToolCall

    /** Wake gate: only READY/PAUSED/INPUT_REQUIRED accept an explicit user wake (ADR-0004). */
    fun wakeAllowed(state: GoalState): Boolean = state in WAKE_STATES

    private val WAKE_STATES = setOf(GoalState.READY, GoalState.PAUSED, GoalState.INPUT_REQUIRED)
}
