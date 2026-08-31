package com.helix.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.helix.core.model.TurnState as Phase

class TurnReducerLifecycleTest {
    @Test
    fun submittedMovesToBuildingContextWithEffect() {
        val step = TurnReducer.reduce(Fixtures.newTurn(), TurnEvent.Lifecycle.TurnSubmitted)
        assertEquals(Phase.BUILDING_CONTEXT, step.state.phase)
        assertEquals(listOf<TurnEffect>(TurnEffect.BuildContext), step.effects)
        assertFalse(step.ignored)
    }

    @Test
    fun submittedIsIgnoredOutsideCreated() {
        val step = TurnReducer.reduce(driveTo(Phase.BUILDING_CONTEXT), TurnEvent.Lifecycle.TurnSubmitted)
        assertTrue(step.ignored)
        assertEquals(Phase.BUILDING_CONTEXT, step.state.phase)
    }

    @Test
    fun finalTextCompletesTurn() {
        val state = driveTo(Phase.RECEIVING_MODEL)
        val step =
            TurnReducer.reduce(
                state,
                TurnEvent.Model.Finished(activeCall(state), 800, ModelTerminal.FinalText("stop")),
            )
        assertEquals(Phase.COMPLETED, step.state.phase)
        assertEquals("stop", step.state.finishReason)
        assertNull(step.state.error)
        assertEquals(listOf<TurnEffect>(TurnEffect.CompleteTurn("stop")), step.effects)
    }

    @Test
    fun refusalCompletesTurnWithRefusalReason() {
        val state = driveTo(Phase.RECEIVING_MODEL)
        val step =
            TurnReducer.reduce(
                state,
                TurnEvent.Model.Finished(activeCall(state), 800, ModelTerminal.Refusal(null)),
            )
        assertEquals(Phase.COMPLETED, step.state.phase)
        assertEquals("refusal", step.state.finishReason)
    }

    @Test
    fun providerFinishReasonIsPreserved() {
        val state = driveTo(Phase.RECEIVING_MODEL)
        val step =
            TurnReducer.reduce(
                state,
                TurnEvent.Model.Finished(activeCall(state), 800, ModelTerminal.FinalText("length")),
            )
        assertEquals("length", step.state.finishReason)
    }

    @Test
    fun finalTextWithoutFinishReasonCompletesWithCanonicalReason() {
        // A provider end-of-stream without a reason is legitimate (doc 02 6.1: nullable
        // reason): the turn completes with the canonical "stop", it must never be a verify()
        // crash.
        val state = driveTo(Phase.RECEIVING_MODEL)
        val step =
            TurnReducer.reduce(
                state,
                TurnEvent.Model.Finished(activeCall(state), 800, ModelTerminal.FinalText(null)),
            )
        assertEquals(Phase.COMPLETED, step.state.phase)
        assertEquals("stop", step.state.finishReason)
        assertNull(step.state.error)
        assertEquals(listOf<TurnEffect>(TurnEffect.CompleteTurn("stop")), step.effects)
    }

    @Test
    fun eventsAfterCompletionAreIgnored() {
        val completed = driveTo(Phase.COMPLETED)
        for (event in listOf<TurnEvent>(
            TurnEvent.Lifecycle.TurnSubmitted,
            TurnEvent.Lifecycle.ContextReady(Fixtures.call(9), 100),
            TurnEvent.Lifecycle.CancelRequested,
            TurnEvent.Tool.ResultsRecorded,
        )) {
            val step = TurnReducer.reduce(completed, event)
            assertTrue("event $event must be ignored", step.ignored)
            assertEquals(completed, step.state)
        }
    }
}
