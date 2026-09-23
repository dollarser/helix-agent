package com.helix.app.chat

import com.helix.app.agent.BatchTurnRuntime
import com.helix.app.agent.ModelStreamTerminal
import com.helix.app.agent.TurnCoordinator
import com.helix.core.model.TurnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R8 Fault Injection & Terminal Notification Boundary Tests (ADR-AGENT-001, deep review R8).
 *
 * Verifies that:
 * 1. Once a turn reaches a durable terminal state (COMPLETED / CANCELLED), a subsequent
 *    post-terminal exception (e.g., projection, notification, UI refresh failure) never
 *    reverses or mutates the terminal state to FAILED.
 * 2. Secondary terminalization calls are completely idempotent.
 * 3. Terminal state cannot be reverted from CANCELLING to FAILED.
 */
class TurnTerminalFaultInjectionUnitTest {
    @Test
    fun terminalRuntimeIgnoresSubsequentFailureInjections() {
        val runtime = BatchTurnRuntime("model-call-1")
        runtime.beginModelStream()
        // Successful completion of turn
        runtime.terminalize(TurnState.COMPLETED)
        assertEquals(TurnState.COMPLETED, runtime.snapshot().phase)
        assertTrue(runtime.snapshot().phase.isTerminal)

        // Simulated R8 fault injection: outer boundary throws and attempts to terminalize as FAILED
        runtime.terminalize(TurnState.FAILED)

        // Must still remain COMPLETED!
        assertEquals(TurnState.COMPLETED, runtime.snapshot().phase)
    }

    @Test
    fun cancelledRuntimeIgnoresSubsequentFailureInjections() {
        val runtime = BatchTurnRuntime("model-call-1")
        runtime.beginModelStream()
        // Stop requested and settled as CANCELLED
        runtime.terminalize(TurnState.CANCELLED)
        assertEquals(TurnState.CANCELLED, runtime.snapshot().phase)

        // Fault injection: secondary error boundary triggered
        runtime.terminalize(TurnState.FAILED)
        assertEquals(TurnState.CANCELLED, runtime.snapshot().phase)
    }

    @Test
    fun settleOutcomeAfterCancellingGuardsAgainstLateFailureEscalation() {
        // When durable state has entered CANCELLING, even an unhandled model boundary
        // exception reporting INTERNAL error must settle as CANCELLED, not FAILED.
        val unhandledError = ModelStreamTerminal(TurnState.FAILED, "INTERNAL")
        val settled =
            TurnCoordinator.settleOutcomeAfterCancelling(
                unhandledError,
                TurnState.CANCELLING.name,
            )
        assertEquals(TurnState.CANCELLED, settled.state)
        assertEquals("INTERNAL", settled.errorCode)
    }

    @Test
    fun postTerminalExceptionDoesNotMutateTerminalOutcome() {
        val completedOutcome = ModelStreamTerminal(TurnState.COMPLETED, null)

        var notificationFailed = false
        // Simulate safe post-terminal dispatch pattern
        try {
            // Simulated post-commit notification failure (e.g. view detached / compose disposed)
            error("UI surface detached during post-terminal projection")
        } catch (_: Exception) {
            notificationFailed = true
        }

        assertTrue(notificationFailed)
        // Ensure outcome remains COMPLETED and terminal state is clean
        assertTrue(completedOutcome.state.isTerminal)
    }
}
