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
    }

    /** Every call executing at process death; all of their external effects are independently unclear. */
    val runningCallIds: List<ToolCallId>
        get() = toolCalls.filter { it.state == ToolCallState.RUNNING }.map { it.callId }
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

    /** Mark the turn INTERRUPTED; every [uncertainToolCalls] entry needs side-effect review first. */
    data class Interrupt(
        val turnId: TurnId,
        val uncertainToolCalls: List<ToolCallId>,
    ) : TurnRecovery
}

/** Decision for one persisted tool call. */
sealed interface ToolCallRecovery {
    /** Durable state (NEEDS_REVIEW, INTERRUPTED, terminal): unchanged. */
    data object Keep : ToolCallRecovery

    /** The row never crossed execution-start; cancel it and persist a deterministic result. */
    data object CancelNotStarted : ToolCallRecovery

    /** The call crossed execution-start and died in flight: park in INTERRUPTED, never replay. */
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
    val cancelledToolCalls: List<ToolCallParking>,
    val parkedToolCalls: List<ToolCallParking>,
    val parkedGoals: List<GoalRecovery.Park>,
) {
    val isEmpty: Boolean
        get() =
            interruptedTurns.isEmpty() &&
                cancelledToolCalls.isEmpty() &&
                parkedToolCalls.isEmpty() &&
                parkedGoals.isEmpty()
}

/**
 * Process-death recovery coordinator (HXA-015). Pure decision layer over persisted facts
 * (doc 02 section 5.2, doc 07 section 7.1, ADR-0004):
 *
 * - any non-terminal turn that is not already INTERRUPTED becomes INTERRUPTED; every RUNNING
 *   call is an independently uncertain external effect;
 * - PENDING/AWAITING_APPROVAL are deterministically not-started and are cancelled; RUNNING is
 *   parked INTERRUPTED; NEEDS_REVIEW/INTERRUPTED remain durable facts;
 * - a RUNNING goal parks in PAUSED; every other goal state is durable;
 * - INTERRUPTED is execution-terminal; continuation is a successor Turn, never old-Turn resume;
 * - a wake (USER_OPEN/NOTIFICATION_ACTION) is only accepted from READY/PAUSED/INPUT_REQUIRED
 *   (the [GoalReducer] `Continued` gate), so a stale wake against a RUNNING or terminal goal
 *   is dropped.
 */
object RecoveryCoordinator {
    fun recoveryForTurn(turn: PersistedTurn): TurnRecovery =
        when {
            turn.phase.isTerminal -> TurnRecovery.NoAction
            turn.phase in setOf(TurnState.NEEDS_REVIEW, TurnState.INTERRUPTED) -> TurnRecovery.NoAction
            else -> TurnRecovery.Interrupt(turn.turnId, turn.runningCallIds)
        }

    fun recoveryForToolCall(call: PersistedToolCall): ToolCallRecovery =
        when (call.state) {
            ToolCallState.PENDING, ToolCallState.AWAITING_APPROVAL -> ToolCallRecovery.CancelNotStarted
            ToolCallState.RUNNING -> ToolCallRecovery.ParkInterrupted
            else -> ToolCallRecovery.Keep
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
        // This pure plan handles calls under active/non-terminal turns. Terminal parents can
        // still contain incomplete child settlement after a crash between parent and child
        // commits; the app/storage recovery layer reconciles that separate repair case.
        val parkedToolCalls =
            turns
                .filter { turn -> !turn.phase.isTerminal }
                .flatMap { turn ->
                    turn.toolCalls
                        .filter { call -> recoveryForToolCall(call) == ToolCallRecovery.ParkInterrupted }
                        .map { call -> ToolCallParking(turn.turnId, call.callId) }
                }.sortedWith(compareBy({ it.turnId.value }, { it.toolCallId.value }))
        val cancelledToolCalls =
            turns
                .filter { turn -> !turn.phase.isTerminal }
                .flatMap { turn ->
                    turn.toolCalls
                        .filter { call -> recoveryForToolCall(call) == ToolCallRecovery.CancelNotStarted }
                        .map { call -> ToolCallParking(turn.turnId, call.callId) }
                }.sortedWith(compareBy({ it.turnId.value }, { it.toolCallId.value }))
        val parkedGoals =
            goals
                .mapNotNull { goal -> recoveryForGoal(goal) as? GoalRecovery.Park }
                .sortedBy { it.goalId.value }
        return RecoveryPlan(interruptedTurns, cancelledToolCalls, parkedToolCalls, parkedGoals)
    }

    /** Wake gate: only READY/PAUSED/INPUT_REQUIRED accept an explicit user wake (ADR-0004). */
    fun wakeAllowed(state: GoalState): Boolean = state in WAKE_STATES

    private val WAKE_STATES = setOf(GoalState.READY, GoalState.PAUSED, GoalState.INPUT_REQUIRED)
}
