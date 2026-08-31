package com.helix.app.recovery

import com.helix.core.agent.GoalRecovery
import com.helix.core.agent.PersistedGoal
import com.helix.core.agent.PersistedToolCall
import com.helix.core.agent.PersistedTurn
import com.helix.core.agent.RecoveryCoordinator
import com.helix.core.agent.TurnRecovery
import com.helix.core.model.Clock
import com.helix.core.model.GoalId
import com.helix.core.model.GoalState
import com.helix.core.model.ToolCallId
import com.helix.core.model.ToolCallState
import com.helix.core.model.TurnId
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.mapping.enumByName
import com.helix.core.storage.mapping.turnStateName
import java.util.UUID

/**
 * Process-restart recovery (HXA-015): runs when the app starts after any kind of process death
 * (crash, kill, power loss; doc 02 section 5.2, section 11 "强制停止后无法自恢复" — the app
 * recovers its state at the next start, it never resurrects mid-death).
 *
 * [RecoveryCoordinator] (core:agent) makes the decisions from the persisted facts; this class
 * pairs the resulting writes with their audit events in a single [HelixStorage.withTransaction]
 * (doc 9.2: a Turn/ToolCall state update and its audit event commit together).
 *
 * Invariants (asserted by the process-recovery fixture):
 * - a leftover non-terminal Turn is marked INTERRUPTED and its in-flight (PENDING/RUNNING)
 *   calls are parked INTERRUPTED; a call still in AWAITING_APPROVAL never executed and keeps
 *   its state;
 * - a RUNNING Goal parks in PAUSED with its checkpoint kept and in-flight wake tracking reset
 *   (ADR-0004); every run row still open for that goal is closed with outcome `INTERRUPTED`
 *   and the usage it had already persisted;
 * - nothing is re-executed: the plan only marks/parks/closes, and a recovered turn or goal may
 *   only continue through the explicit resume/wake gates (core:agent `RecoveryCoordinator`
 *   + the reducers);
 * - the operation is idempotent: a second start finds no active turns, no RUNNING goals and no
 *   open runs, and writes nothing.
 */
class RecoveryCoordinatorApp(
    private val storage: HelixStorage,
    private val clock: Clock,
) {
    /** Deterministic summary of one recovery pass. */
    data class Report(
        /** turnId to the uncertain tool call that needs side-effect review (null: none). */
        val interruptedTurns: Map<String, String?>,
        /** turnId to the call ids parked in INTERRUPTED (only turns with parked calls). */
        val parkedToolCalls: Map<String, List<String>>,
        val parkedGoals: List<String>,
        val closedRuns: List<String>,
    )

    private data class TurnApplied(
        val turnId: String,
        val uncertainToolCall: String?,
        val parkedCalls: List<String>,
    )

    private data class GoalApplied(
        val goalId: String,
        val closedRuns: List<String>,
    )

    /**
     * Scans the persisted state of the previous process and applies the recovery plan
     * atomically. Safe to run on every start; a pass over an already-recovered database is a
     * no-op (no rows written, no audit events).
     */
    fun recover(): Report {
        val now = clock.now().toEpochMilli()
        val plan = RecoveryCoordinator.plan(scanPersistedTurns(), scanPersistedGoals())
        val appliedTurns = mutableListOf<TurnApplied>()
        val appliedGoals = mutableListOf<GoalApplied>()
        storage.withTransaction {
            plan.interruptedTurns.forEach { interrupt ->
                appliedTurns += applyTurnInterruption(interrupt, now)
            }
            plan.parkedGoals.forEach { park ->
                appliedGoals += applyGoalPark(park, now)
            }
        }
        return Report(
            interruptedTurns = appliedTurns.associate { applied -> applied.turnId to applied.uncertainToolCall },
            parkedToolCalls =
                appliedTurns
                    .filter { applied -> applied.parkedCalls.isNotEmpty() }
                    .associate { applied -> applied.turnId to applied.parkedCalls },
            parkedGoals = appliedGoals.map { applied -> applied.goalId },
            closedRuns = appliedGoals.flatMap { applied -> applied.closedRuns },
        )
    }

    /** The persisted facts of every non-terminal turn (doc 9.1 `turns` + `tool_calls` rows). */
    private fun scanPersistedTurns(): List<PersistedTurn> =
        storage.turns.listActive().map { turn ->
            PersistedTurn(
                turnId = TurnId(turn.id),
                phase = turnStateName(turn.state),
                toolCalls =
                    storage.toolCalls.listByTurn(turn.id).map { call ->
                        PersistedToolCall(
                            callId = ToolCallId(call.callId),
                            state = toolCallState(call.state),
                        )
                    },
            )
        }

    private fun scanPersistedGoals(): List<PersistedGoal> =
        storage.goals
            .listByState(GoalState.RUNNING.name)
            .map { goal -> PersistedGoal(GoalId(goal.id), GoalState.RUNNING) }

    /** Marks the turn INTERRUPTED, parks its in-flight calls, and writes both audit events. */
    private fun applyTurnInterruption(
        interrupt: TurnRecovery.Interrupt,
        now: Long,
    ): TurnApplied {
        val turn = storage.turns.resolve(interrupt.turnId.value)
        val parked =
            storage.toolCalls
                .listByTurn(turn.id)
                .filter { call -> toolCallState(call.state).canBecomeInterruptedOnProcessDeath() }
                .map { call ->
                    storage.toolCalls.updateState(call, ToolCallState.INTERRUPTED)
                    call.callId
                }.sorted()
        storage.turns.updateState(turn, TurnState.INTERRUPTED, turn.stepCount, now, null)
        val uncertain = interrupt.uncertainToolCall?.value
        audit(
            correlationId = turn.sessionId,
            type = "recovery.turn_interrupted",
            payload = """{"turn":"${turn.id}","uncertainToolCall":${uncertain?.let { "\"$it\"" } ?: "null"}}""",
            at = now,
        )
        if (parked.isNotEmpty()) {
            audit(
                correlationId = turn.sessionId,
                type = "recovery.tool_calls_parked",
                payload =
                    """{"turn":"${turn.id}","toolCalls":[${parked.joinToString(",") { "\"$it\"" }}]}""",
                at = now,
            )
        }
        return TurnApplied(turn.id, uncertain, parked)
    }

    /**
     * Parks a RUNNING goal in PAUSED and closes its open runs (ADR-0004: the checkpoint is kept
     * — it survives as a wake source; the in-flight wake is dropped, the same semantics as
     * `GoalReducer.afterProcessDeath` on the domain Goal).
     */
    private fun applyGoalPark(
        park: GoalRecovery.Park,
        now: Long,
    ): GoalApplied {
        val goal = storage.goals.resolve(park.goalId.value)
        storage.goals.updateGoal(goal.copy(state = GoalState.PAUSED.name, currentWakeMillis = 0L))
        val closedRuns =
            storage.goalRuns.listOpenByGoal(goal.id).map { run ->
                storage.goalRuns.finish(
                    run,
                    OUTCOME_INTERRUPTED,
                    now,
                    (now - run.startedAt).coerceAtLeast(0L),
                    run.modelCalls,
                    run.toolCalls,
                    run.tokens,
                )
                audit(
                    correlationId = goal.correlationId,
                    type = "recovery.run_closed",
                    payload =
                        """{"goal":"${goal.id}","run":"${run.id}","outcome":"$OUTCOME_INTERRUPTED"}""",
                    at = now,
                )
                run.id
            }
        audit(
            correlationId = goal.correlationId,
            type = "recovery.goal_parked",
            payload = """{"goal":"${goal.id}"}""",
            at = now,
        )
        return GoalApplied(goal.id, closedRuns)
    }

    private fun toolCallState(name: String): ToolCallState {
        val state = enumByName(name, ToolCallState::class.java, "tool call state")
        return state
    }

    private fun audit(
        correlationId: String,
        type: String,
        payload: String,
        at: Long,
    ) {
        storage.auditEvents.append(
            id = "recovery-${UUID.randomUUID()}",
            correlationId = correlationId,
            type = type,
            actor = "recovery",
            redactedPayload = payload,
            timestamp = at,
        )
    }

    private companion object {
        const val OUTCOME_INTERRUPTED = "INTERRUPTED"
    }
}
