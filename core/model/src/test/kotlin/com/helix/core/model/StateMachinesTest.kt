package com.helix.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class StateMachinesTest {
    // region TurnState

    private val expectedTurnTransitions =
        setOf(
            TurnState.CREATED to TurnState.BUILDING_CONTEXT,
            TurnState.BUILDING_CONTEXT to TurnState.WAITING_MODEL,
            TurnState.BUILDING_CONTEXT to TurnState.FAILED,
            TurnState.WAITING_MODEL to TurnState.RECEIVING_MODEL,
            TurnState.WAITING_MODEL to TurnState.FAILED,
            TurnState.RECEIVING_MODEL to TurnState.WAITING_APPROVAL,
            TurnState.RECEIVING_MODEL to TurnState.RUNNING_TOOL,
            TurnState.RECEIVING_MODEL to TurnState.COMPLETED,
            TurnState.RECEIVING_MODEL to TurnState.FAILED,
            TurnState.WAITING_APPROVAL to TurnState.RUNNING_TOOL,
            TurnState.WAITING_APPROVAL to TurnState.RECORDING_TOOL_RESULT,
            TurnState.RUNNING_TOOL to TurnState.RECORDING_TOOL_RESULT,
            TurnState.RECORDING_TOOL_RESULT to TurnState.BUILDING_CONTEXT,
            // Serial next call of the same model response (first version, doc 02 section 5.3).
            TurnState.RECORDING_TOOL_RESULT to TurnState.WAITING_APPROVAL,
            TurnState.RECORDING_TOOL_RESULT to TurnState.RUNNING_TOOL,
            // Cancellation from any non-terminal state except CANCELLING and INTERRUPTED.
            TurnState.CREATED to TurnState.CANCELLING,
            TurnState.BUILDING_CONTEXT to TurnState.CANCELLING,
            TurnState.WAITING_MODEL to TurnState.CANCELLING,
            TurnState.RECEIVING_MODEL to TurnState.CANCELLING,
            TurnState.WAITING_APPROVAL to TurnState.CANCELLING,
            TurnState.RUNNING_TOOL to TurnState.CANCELLING,
            TurnState.RECORDING_TOOL_RESULT to TurnState.CANCELLING,
            TurnState.CANCELLING to TurnState.CANCELLED,
            // Recovery paths for an interrupted turn.
            TurnState.INTERRUPTED to TurnState.BUILDING_CONTEXT,
            TurnState.INTERRUPTED to TurnState.CANCELLED,
        )

    @Test
    fun turnTransitionMatrixIsExhaustive() {
        var checked = 0
        for (from in TurnState.entries) {
            for (to in TurnState.entries) {
                val expected = (from to to) in expectedTurnTransitions
                assertEquals("turn $from -> $to", expected, from.canTransitionTo(to))
                checked += 1
            }
        }
        assertEquals(TurnState.entries.size * TurnState.entries.size, checked)
    }

    @Test
    fun turnTerminalStatesHaveNoOutgoingTransitions() {
        assertEquals(setOf(TurnState.COMPLETED, TurnState.FAILED, TurnState.CANCELLED), TurnState.TERMINAL)
        for (state in TurnState.entries) {
            assertEquals("isTerminal flag for $state", state in TurnState.TERMINAL, state.isTerminal)
        }
    }

    @Test
    fun turnProcessDeathInterruptsOnlyNonTerminalStates() {
        for (state in TurnState.entries) {
            val expected = !state.isTerminal && state != TurnState.INTERRUPTED
            assertEquals("process death from $state", expected, state.canBecomeInterruptedOnProcessDeath())
        }
    }

    // endregion

    // region ToolCallState

    private val expectedToolCallTransitions =
        setOf(
            ToolCallState.PENDING to ToolCallState.AWAITING_APPROVAL,
            ToolCallState.PENDING to ToolCallState.RUNNING,
            ToolCallState.PENDING to ToolCallState.DENIED,
            ToolCallState.PENDING to ToolCallState.FAILED,
            ToolCallState.PENDING to ToolCallState.CANCELLED,
            ToolCallState.AWAITING_APPROVAL to ToolCallState.RUNNING,
            ToolCallState.AWAITING_APPROVAL to ToolCallState.DENIED,
            ToolCallState.AWAITING_APPROVAL to ToolCallState.FAILED,
            ToolCallState.AWAITING_APPROVAL to ToolCallState.CANCELLED,
            ToolCallState.RUNNING to ToolCallState.COMPLETED,
            ToolCallState.RUNNING to ToolCallState.FAILED,
            ToolCallState.RUNNING to ToolCallState.CANCELLED,
            ToolCallState.RUNNING to ToolCallState.NEEDS_REVIEW,
        )

    @Test
    fun toolCallTransitionMatrixIsExhaustive() {
        var checked = 0
        for (from in ToolCallState.entries) {
            for (to in ToolCallState.entries) {
                val expected = (from to to) in expectedToolCallTransitions
                assertEquals("toolCall $from -> $to", expected, from.canTransitionTo(to))
                checked += 1
            }
        }
        assertEquals(ToolCallState.entries.size * ToolCallState.entries.size, checked)
    }

    @Test
    fun toolCallTerminalAndParkedStatesAreStable() {
        assertEquals(
            setOf(ToolCallState.COMPLETED, ToolCallState.FAILED, ToolCallState.CANCELLED, ToolCallState.DENIED),
            ToolCallState.TERMINAL,
        )
        val parked = ToolCallState.entries.filter { it.isParked }.toSet()
        assertEquals(setOf(ToolCallState.NEEDS_REVIEW, ToolCallState.INTERRUPTED), parked)
        // NEEDS_REVIEW and INTERRUPTED never auto-retry or auto-transition.
        for (state in ToolCallState.entries.filter { it.isParked || it.isTerminal }) {
            for (to in ToolCallState.entries) {
                assertEquals("toolCall $state -> $to", false, state.canTransitionTo(to))
            }
        }
    }

    @Test
    fun toolCallProcessDeathOnlyParksInFlightCalls() {
        for (state in ToolCallState.entries) {
            val expected = state == ToolCallState.PENDING || state == ToolCallState.RUNNING
            assertEquals("toolCall process death from $state", expected, state.canBecomeInterruptedOnProcessDeath())
        }
    }

    // endregion

    // region ExecutionState

    private val expectedExecutionTransitions =
        setOf(
            ExecutionState.PENDING to ExecutionState.RUNNING,
            ExecutionState.PENDING to ExecutionState.CANCELLED,
            ExecutionState.PENDING to ExecutionState.FAILED,
            ExecutionState.RUNNING to ExecutionState.COMPLETED,
            ExecutionState.RUNNING to ExecutionState.FAILED,
            ExecutionState.RUNNING to ExecutionState.CANCELLED,
            ExecutionState.RUNNING to ExecutionState.TIMED_OUT,
        )

    @Test
    fun executionTransitionMatrixIsExhaustive() {
        var checked = 0
        for (from in ExecutionState.entries) {
            for (to in ExecutionState.entries) {
                val expected = (from to to) in expectedExecutionTransitions
                assertEquals("execution $from -> $to", expected, from.canTransitionTo(to))
                checked += 1
            }
        }
        assertEquals(ExecutionState.entries.size * ExecutionState.entries.size, checked)
    }

    @Test
    fun executionTerminalAndParkedStatesAreStable() {
        assertEquals(
            setOf(ExecutionState.COMPLETED, ExecutionState.FAILED, ExecutionState.CANCELLED, ExecutionState.TIMED_OUT),
            ExecutionState.TERMINAL,
        )
        for (state in ExecutionState.entries.filter { it.isParked || it.isTerminal }) {
            for (to in ExecutionState.entries) {
                assertEquals("execution $state -> $to", false, state.canTransitionTo(to))
            }
        }
    }

    @Test
    fun executionProcessDeathOnlyParksInFlightExecutions() {
        for (state in ExecutionState.entries) {
            val expected = state == ExecutionState.PENDING || state == ExecutionState.RUNNING
            assertEquals("execution process death from $state", expected, state.canBecomeInterruptedOnProcessDeath())
        }
    }

    // endregion

    // region GoalState

    private val expectedGoalTransitions =
        setOf(
            GoalState.DRAFT to GoalState.READY,
            GoalState.DRAFT to GoalState.CANCELLED,
            GoalState.READY to GoalState.RUNNING,
            GoalState.READY to GoalState.CANCELLED,
            GoalState.RUNNING to GoalState.INPUT_REQUIRED,
            GoalState.RUNNING to GoalState.PAUSED,
            GoalState.RUNNING to GoalState.COMPLETED,
            GoalState.RUNNING to GoalState.FAILED,
            GoalState.RUNNING to GoalState.CANCELLED,
            GoalState.INPUT_REQUIRED to GoalState.RUNNING,
            GoalState.INPUT_REQUIRED to GoalState.CANCELLED,
            GoalState.PAUSED to GoalState.RUNNING,
            GoalState.PAUSED to GoalState.CANCELLED,
        )

    @Test
    fun goalTransitionMatrixIsExhaustive() {
        var checked = 0
        for (from in GoalState.entries) {
            for (to in GoalState.entries) {
                val expected = (from to to) in expectedGoalTransitions
                assertEquals("goal $from -> $to", expected, from.canTransitionTo(to))
                checked += 1
            }
        }
        assertEquals(GoalState.entries.size * GoalState.entries.size, checked)
    }

    @Test
    fun goalTerminalStatesHaveNoOutgoingTransitions() {
        assertEquals(setOf(GoalState.COMPLETED, GoalState.FAILED, GoalState.CANCELLED), GoalState.TERMINAL)
        for (state in GoalState.TERMINAL) {
            for (to in GoalState.entries) {
                assertEquals("goal $state -> $to", false, state.canTransitionTo(to))
            }
        }
    }

    @Test
    fun goalProcessDeathOnlyParksRunningGoals() {
        for (state in GoalState.entries) {
            val expected = if (state == GoalState.RUNNING) GoalState.PAUSED else state
            assertEquals("goal process death from $state", expected, state.stateAfterProcessDeath())
        }
    }

    // endregion
}
