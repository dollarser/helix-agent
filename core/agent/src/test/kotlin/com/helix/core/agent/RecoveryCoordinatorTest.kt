package com.helix.core.agent

import com.helix.core.model.ErrorCode
import com.helix.core.model.GoalId
import com.helix.core.model.GoalState
import com.helix.core.model.ToolCallId
import com.helix.core.model.ToolCallState
import com.helix.core.model.ToolName
import com.helix.core.model.ToolVersion
import com.helix.core.model.TurnId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.helix.core.model.TurnState as Phase

/**
 * HXA-015 recovery coordinator decisions over persisted facts (doc 02 section 5.2, doc 07
 * section 7.1, ADR-0004). Pure JVM; the storage wiring and the process-restart device fixture
 * are in `:app`.
 */
class RecoveryCoordinatorTest {
    private fun turn(n: Int) = TurnId("turn-$n")

    private fun persistedTurn(
        id: TurnId,
        phase: Phase,
        vararg calls: Pair<String, ToolCallState>,
    ): PersistedTurn =
        PersistedTurn(
            turnId = id,
            phase = phase,
            toolCalls = calls.map { (callId, state) -> PersistedToolCall(toolId(callId), state) },
        )

    private fun persistedCall(
        id: String,
        state: ToolCallState,
    ) = PersistedToolCall(toolId(id), state)

    private fun toolId(name: String) = ToolCallId(name)

    private fun pending(
        id: ToolCallId,
        state: ToolCallState,
    ): PendingToolCall = PendingToolCall(id, ToolName("write"), ToolVersion(1), false, null, state)

    // ---------------------------------------------------------------- turn decisions

    @Test
    fun `terminal turns are left alone`() {
        for (phase in listOf(Phase.COMPLETED, Phase.FAILED, Phase.CANCELLED)) {
            assertEquals(TurnRecovery.NoAction, RecoveryCoordinator.recoveryForTurn(persistedTurn(turn(1), phase)))
        }
    }

    @Test
    fun `an already interrupted turn is a no-op (idempotent recovery)`() {
        val decision =
            RecoveryCoordinator.recoveryForTurn(
                persistedTurn(turn(1), Phase.INTERRUPTED, "c1" to ToolCallState.INTERRUPTED),
            )
        assertEquals(TurnRecovery.NoAction, decision)
    }

    @Test
    fun `a waiting model turn parks without an uncertain call`() {
        assertEquals(
            TurnRecovery.Interrupt(turn(1), null),
            RecoveryCoordinator.recoveryForTurn(persistedTurn(turn(1), Phase.WAITING_MODEL)),
        )
    }

    @Test
    fun `a running tool turn marks the executing call uncertain`() {
        val decision =
            RecoveryCoordinator.recoveryForTurn(
                persistedTurn(
                    turn(1),
                    Phase.RUNNING_TOOL,
                    "c1" to ToolCallState.RUNNING,
                    "c2" to ToolCallState.PENDING,
                ),
            )
        assertEquals(TurnRecovery.Interrupt(turn(1), toolId("c1")), decision)
    }

    @Test
    fun `a cancelling turn keeps its tracked candidate uncertain`() {
        val decision =
            RecoveryCoordinator.recoveryForTurn(
                persistedTurn(turn(1), Phase.CANCELLING, "c1" to ToolCallState.RUNNING),
            )
        assertEquals(TurnRecovery.Interrupt(turn(1), toolId("c1")), decision)
    }

    @Test
    fun `a call still awaiting approval never executed and is not uncertain`() {
        val decision =
            RecoveryCoordinator.recoveryForTurn(
                persistedTurn(turn(1), Phase.WAITING_APPROVAL, "c1" to ToolCallState.AWAITING_APPROVAL),
            )
        assertEquals(TurnRecovery.Interrupt(turn(1), null), decision)
    }

    @Test
    fun `recording a result parks only the queued call`() {
        val turnState =
            persistedTurn(
                turn(1),
                Phase.RECORDING_TOOL_RESULT,
                "c1" to ToolCallState.COMPLETED,
                "c2" to ToolCallState.PENDING,
            )
        assertEquals(TurnRecovery.Interrupt(turn(1), null), RecoveryCoordinator.recoveryForTurn(turnState))
        val plan = RecoveryCoordinator.plan(listOf(turnState), emptyList())
        assertEquals(listOf(ToolCallParking(turn(1), toolId("c2"))), plan.parkedToolCalls)
    }

    @Test
    fun `two running calls are a corrupt input`() {
        assertThrows<IllegalArgumentException>("serial execution allows one RUNNING call") {
            persistedTurn(
                turn(1),
                Phase.RUNNING_TOOL,
                "c1" to ToolCallState.RUNNING,
                "c2" to ToolCallState.RUNNING,
            )
        }
    }

    @Test
    fun `duplicate tool call ids are a corrupt input`() {
        assertThrows<IllegalArgumentException>("unique call ids") {
            persistedTurn(
                turn(1),
                Phase.WAITING_MODEL,
                "c1" to ToolCallState.PENDING,
                "c1" to ToolCallState.PENDING,
            )
        }
    }

    // ---------------------------------------------------------------- tool call decisions

    @Test
    fun `only in-flight calls are parked`() {
        assertEquals(
            ToolCallRecovery.ParkInterrupted,
            RecoveryCoordinator.recoveryForToolCall(persistedCall("c1", ToolCallState.PENDING)),
        )
        assertEquals(
            ToolCallRecovery.ParkInterrupted,
            RecoveryCoordinator.recoveryForToolCall(persistedCall("c1", ToolCallState.RUNNING)),
        )
    }

    @Test
    fun `durable call states are kept`() {
        val durable =
            listOf(
                ToolCallState.AWAITING_APPROVAL,
                ToolCallState.NEEDS_REVIEW,
                ToolCallState.INTERRUPTED,
                ToolCallState.COMPLETED,
                ToolCallState.FAILED,
                ToolCallState.CANCELLED,
                ToolCallState.DENIED,
            )
        for (state in durable) {
            val decision = RecoveryCoordinator.recoveryForToolCall(persistedCall("c1", state))
            assertEquals(state.name, ToolCallRecovery.Keep, decision)
        }
    }

    // ---------------------------------------------------------------- goal decisions

    @Test
    fun `a running goal parks and durable goals stay`() {
        assertEquals(
            GoalRecovery.Park(GoalId("goal-1")),
            RecoveryCoordinator.recoveryForGoal(PersistedGoal(GoalId("goal-1"), GoalState.RUNNING)),
        )
        val durable =
            listOf(
                GoalState.DRAFT,
                GoalState.READY,
                GoalState.PAUSED,
                GoalState.INPUT_REQUIRED,
                GoalState.COMPLETED,
                GoalState.FAILED,
                GoalState.CANCELLED,
            )
        for (state in durable) {
            val decision = RecoveryCoordinator.recoveryForGoal(PersistedGoal(GoalId("goal-1"), state))
            assertEquals(state.name, GoalRecovery.NoAction, decision)
        }
    }

    // ---------------------------------------------------------------- plan determinism

    @Test
    fun `the plan is deterministic and complete`() {
        val turns =
            listOf(
                persistedTurn(turn(3), Phase.COMPLETED, "c1" to ToolCallState.COMPLETED),
                persistedTurn(turn(2), Phase.WAITING_MODEL),
                persistedTurn(turn(1), Phase.RUNNING_TOOL, "a" to ToolCallState.RUNNING, "b" to ToolCallState.PENDING),
            ).shuffled()
        val goals =
            listOf(
                PersistedGoal(GoalId("goal-b"), GoalState.RUNNING),
                PersistedGoal(GoalId("goal-a"), GoalState.PAUSED),
            ).shuffled()
        val plan = RecoveryCoordinator.plan(turns, goals)
        assertEquals(
            listOf(
                TurnRecovery.Interrupt(turn(1), toolId("a")),
                TurnRecovery.Interrupt(turn(2), null),
            ),
            plan.interruptedTurns,
        )
        assertEquals(
            listOf(
                ToolCallParking(turn(1), toolId("a")),
                ToolCallParking(turn(1), toolId("b")),
            ),
            plan.parkedToolCalls,
        )
        assertEquals(listOf(GoalRecovery.Park(GoalId("goal-b"))), plan.parkedGoals)
        assertFalse(plan.isEmpty)
    }

    @Test
    fun `an empty input gives an empty plan`() {
        assertTrue(RecoveryCoordinator.plan(emptyList(), emptyList()).isEmpty)
    }

    // ---------------------------------------------------------------- gates

    @Test
    fun `the resume gate requires interrupted phase and a resolved uncertain call`() {
        assertTrue(RecoveryCoordinator.canResumeTurn(Phase.INTERRUPTED, hasUncertainToolCall = false))
        assertFalse(RecoveryCoordinator.canResumeTurn(Phase.INTERRUPTED, hasUncertainToolCall = true))
        assertFalse(RecoveryCoordinator.canResumeTurn(Phase.RUNNING_TOOL, hasUncertainToolCall = false))
        assertFalse(RecoveryCoordinator.canResumeTurn(Phase.COMPLETED, hasUncertainToolCall = false))
    }

    @Test
    fun `the wake gate only accepts parked or ready goals`() {
        assertTrue(RecoveryCoordinator.wakeAllowed(GoalState.READY))
        assertTrue(RecoveryCoordinator.wakeAllowed(GoalState.PAUSED))
        assertTrue(RecoveryCoordinator.wakeAllowed(GoalState.INPUT_REQUIRED))
        assertFalse(RecoveryCoordinator.wakeAllowed(GoalState.DRAFT))
        assertFalse(RecoveryCoordinator.wakeAllowed(GoalState.RUNNING))
        assertFalse(RecoveryCoordinator.wakeAllowed(GoalState.COMPLETED))
        assertFalse(RecoveryCoordinator.wakeAllowed(GoalState.FAILED))
        assertFalse(RecoveryCoordinator.wakeAllowed(GoalState.CANCELLED))
    }

    // ---------------------------------------------------------------- cross-check with the reducers

    @Test
    fun `the coordinator agrees with the turn reducer across process death`() {
        // Dying in RUNNING_TOOL: the reducer marks the executing call uncertain, and so does
        // the coordinator from the persisted rows.
        val preDeath =
            TurnState(
                sessionId = Fixtures.session,
                turnId = Fixtures.turn,
                correlationId = Fixtures.correlation,
                phase = Phase.RUNNING_TOOL,
                budgets = Fixtures.budgets(),
                pendingCalls =
                    listOf(
                        pending(toolId("c1"), ToolCallState.RUNNING),
                        pending(toolId("c2"), ToolCallState.PENDING),
                    ),
            )
        val reduced = TurnReducer.afterProcessDeath(preDeath)
        val persisted =
            persistedTurn(
                Fixtures.turn,
                Phase.RUNNING_TOOL,
                "c1" to ToolCallState.RUNNING,
                "c2" to ToolCallState.PENDING,
            )
        assertEquals(Phase.INTERRUPTED, reduced.phase)
        assertEquals(
            TurnRecovery.Interrupt(Fixtures.turn, reduced.uncertainToolCallId),
            RecoveryCoordinator.recoveryForTurn(persisted),
        )

        // Dying in WAITING_APPROVAL: the call never executed; both layers see no uncertainty.
        val awaiting =
            TurnState(
                sessionId = Fixtures.session,
                turnId = Fixtures.turn,
                correlationId = Fixtures.correlation,
                phase = Phase.WAITING_APPROVAL,
                budgets = Fixtures.budgets(),
                pendingCalls = listOf(pending(toolId("c1"), ToolCallState.AWAITING_APPROVAL)),
            )
        val reducedAwaiting = TurnReducer.afterProcessDeath(awaiting)
        val persistedAwaiting =
            persistedTurn(Fixtures.turn, Phase.WAITING_APPROVAL, "c1" to ToolCallState.AWAITING_APPROVAL)
        assertEquals(null, reducedAwaiting.uncertainToolCallId)
        assertEquals(
            TurnRecovery.Interrupt(Fixtures.turn, reducedAwaiting.uncertainToolCallId),
            RecoveryCoordinator.recoveryForTurn(persistedAwaiting),
        )
    }

    @Test
    fun `resuming never replays a call and gates on the uncertain one`() {
        // The coordinator recovered a turn whose executing call c1 is uncertain; c2 was queued
        // and never executed. The runtime state reconstructed from the persisted facts must
        // not be resumable until c1 is resolved.
        val recovered =
            TurnState(
                sessionId = Fixtures.session,
                turnId = Fixtures.turn,
                correlationId = Fixtures.correlation,
                phase = Phase.INTERRUPTED,
                budgets = Fixtures.budgets(),
                pendingCalls = listOf(pending(toolId("c2"), ToolCallState.PENDING)),
                uncertainToolCallId = toolId("c1"),
            )
        assertFalse(RecoveryCoordinator.canResumeTurn(recovered.phase, hasUncertainToolCall = true))

        val ignored = TurnReducer.reduce(recovered, TurnEvent.Lifecycle.TurnResumed)
        assertTrue(ignored.ignored)

        val resolved =
            TurnReducer.reduce(recovered, TurnEvent.Lifecycle.UncertainToolCallResolved(ToolOutcome.TimedOut))
        assertFalse(resolved.ignored)
        assertEquals(null, resolved.state.uncertainToolCallId)

        // Now the user resumes: the turn rebuilds context, c2 is recorded as failed-interrupted
        // (never re-executed), and the only effect is context building — no tool or model work.
        val resumed = TurnReducer.reduce(resolved.state, TurnEvent.Lifecycle.TurnResumed)
        assertFalse(resumed.ignored)
        assertEquals(Phase.BUILDING_CONTEXT, resumed.state.phase)
        // c1 (the resolved uncertain call, outcome TimedOut from the review above) plus c2.
        assertEquals(2, resumed.state.recordedOutcomes.size)
        val c1Outcome = resumed.state.recordedOutcomes.single { it.toolCallId == toolId("c1") }
        assertEquals(ToolOutcome.TimedOut, c1Outcome.outcome)
        val c2Outcome = resumed.state.recordedOutcomes.single { it.toolCallId == toolId("c2") }
        val failed = c2Outcome.outcome as ToolOutcome.Failed
        assertEquals(ErrorCode.INTERRUPTED, failed.error.code)
        assertEquals(listOf<TurnEffect>(TurnEffect.BuildContext), resumed.effects)
    }

    @Test
    fun `a stale wake against a running goal is dropped by the gate`() {
        val goal = runningGoal()
        // A notification arrives while the goal is still seen as RUNNING: the coordinator gate
        // and the reducer gate both reject the wake.
        assertFalse(RecoveryCoordinator.wakeAllowed(goal.state))
        val stale = GoalReducer.reduce(goal, GoalEvent.Continued(GoalWakeReason.NOTIFICATION_ACTION))
        assertTrue(stale.ignored)

        // After process death the same notification is a legitimate wake source.
        val parked = GoalReducer.afterProcessDeath(goal)
        assertEquals(GoalState.PAUSED, parked.state)
        assertTrue(RecoveryCoordinator.wakeAllowed(parked.state))
        val woken = GoalReducer.reduce(parked, GoalEvent.Continued(GoalWakeReason.NOTIFICATION_ACTION))
        assertFalse(woken.ignored)
        assertEquals(GoalState.RUNNING, woken.state.state)
        val startRun = woken.effects.filterIsInstance<GoalEffect.StartRun>().single()
        assertEquals(GoalWakeReason.NOTIFICATION_ACTION, startRun.wakeReason)
    }
}
