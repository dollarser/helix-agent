package com.helix.core.agent

import com.helix.core.model.TurnState
import org.junit.Assert.assertEquals
import org.junit.Test

class TurnCommitPolicyTest {
    private val expected = TurnCommitExpectation("s", "t", TurnState.RECEIVING_MODEL, "m", 2, false)
    private val actual =
        StoredTurnCommitState("s", "t", TurnState.RECEIVING_MODEL, 2, "t", "RUNNING", null, modelCallId = "m")

    @Test fun matchingCheckpointMayCommit() {
        assertEquals(TurnCommitDecision.Apply, TurnCommitPolicy.terminal(expected, actual))
    }

    @Test fun durableCancellationStillAdmitsTheOriginalOwner() {
        assertEquals(
            TurnCommitDecision.Apply,
            TurnCommitPolicy.terminal(expected, actual.copy(phase = TurnState.CANCELLING)),
        )
    }

    @Test fun anotherSessionCannotPublishAnAssistantMessage() {
        assertEquals(
            TurnCommitDecision.Conflict("TURN_SESSION_MISMATCH"),
            TurnCommitPolicy.terminal(expected.copy(sessionId = "other"), actual),
        )
    }

    @Test fun anotherTurnsModelCallCannotBeClosed() {
        assertEquals(
            TurnCommitDecision.Conflict("MODEL_CALL_TURN_MISMATCH"),
            TurnCommitPolicy.terminal(expected, actual.copy(modelCallTurnId = "other")),
        )
    }

    @Test fun staleStepCannotFinishTheNewStep() {
        assertEquals(
            TurnCommitDecision.Conflict("TURN_STEP_CONFLICT"),
            TurnCommitPolicy.terminal(expected, actual.copy(modelStep = 3)),
        )
    }

    @Test fun futureStepCannotSkipForward() {
        assertEquals(
            TurnCommitDecision.Conflict("TURN_STEP_CONFLICT"),
            TurnCommitPolicy.terminal(expected.copy(modelStep = 3), actual),
        )
    }

    @Test fun phaseConflictIsNotASuccess() {
        assertEquals(
            TurnCommitDecision.Conflict("TURN_PHASE_CONFLICT"),
            TurnCommitPolicy.terminal(expected, actual.copy(phase = TurnState.RUNNING_TOOL)),
        )
    }

    @Test fun newRunningModelOwnerCannotBeOverwritten() {
        assertEquals(
            TurnCommitDecision.Conflict("MODEL_CALL_OWNER_CONFLICT"),
            TurnCommitPolicy.terminal(expected, actual.copy(hasOtherRunningModelCall = true)),
        )
    }

    @Test fun closedModelRequiresAnExplicitClosedCheckpoint() {
        assertEquals(
            TurnCommitDecision.Conflict("MODEL_CALL_CLOSED"),
            TurnCommitPolicy.terminal(expected, actual.copy(modelCallState = "COMPLETED")),
        )
        assertEquals(
            TurnCommitDecision.Apply,
            TurnCommitPolicy.terminal(expected.copy(modelCallClosed = true), actual.copy(modelCallState = "COMPLETED")),
        )
    }

    @Test fun duplicateTerminalReturnsTheDurableOutcome() {
        assertEquals(
            TurnCommitDecision.AlreadyApplied(TurnState.CANCELLED, "STOP"),
            TurnCommitPolicy.terminal(
                expected,
                actual.copy(phase = TurnState.CANCELLED, modelCallState = "CANCELLED", errorCode = "STOP"),
            ),
        )
    }

    @Test fun wrongSessionCannotReadAnExistingTerminalReceipt() {
        assertEquals(
            TurnCommitDecision.Conflict("TURN_SESSION_MISMATCH"),
            TurnCommitPolicy.terminal(expected.copy(sessionId = "other"), actual.copy(phase = TurnState.COMPLETED)),
        )
    }

    @Test fun sameTurnDifferentCallCannotSubstituteTheExpectedIdentity() {
        assertEquals(
            TurnCommitDecision.Conflict("MODEL_CALL_ID_MISMATCH"),
            TurnCommitPolicy.terminal(expected, actual.copy(modelCallId = "other")),
        )
    }

    @Test fun closedCheckpointCannotLeaveARunningCallBehind() {
        assertEquals(
            TurnCommitDecision.Conflict("MODEL_CALL_NOT_CLOSED"),
            TurnCommitPolicy.terminal(expected.copy(modelCallClosed = true), actual),
        )
    }

    @Test fun missingFactsAreUnavailableNotApplied() {
        assertEquals(TurnCommitDecision.Unavailable("TURN_NOT_FOUND"), TurnCommitPolicy.terminal(expected, null))
        assertEquals(
            TurnCommitDecision.Unavailable("MODEL_CALL_NOT_FOUND"),
            TurnCommitPolicy.terminal(expected, actual.copy(modelCallTurnId = null, modelCallState = null)),
        )
    }
}
