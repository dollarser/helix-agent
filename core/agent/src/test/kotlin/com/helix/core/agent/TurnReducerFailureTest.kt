package com.helix.core.agent

import com.helix.core.model.ErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.helix.core.model.TurnState as Phase

class TurnReducerFailureTest {
    @Test
    fun callFailedBeforeStreamFailsTurn() {
        val waiting = driveTo(Phase.WAITING_MODEL)
        val error = Fixtures.error(ErrorCode.PROVIDER_AUTH, "provider rejected the request")
        val step =
            TurnReducer.reduce(waiting, TurnEvent.Model.CallFailed(Fixtures.call(1), 0, error))
        assertEquals(Phase.FAILED, step.state.phase)
        assertEquals(error, step.state.error)
        assertNull(step.state.finishReason)
        val effect = step.effects.single() as TurnEffect.FailTurn
        assertEquals(ErrorCode.PROVIDER_AUTH, effect.error.code)
    }

    @Test
    fun callFailedMidStreamFailsTurn() {
        val receiving = driveTo(Phase.RECEIVING_MODEL)
        val step =
            TurnReducer.reduce(receiving, TurnEvent.Model.CallFailed(Fixtures.call(1), 128, Fixtures.error()))
        assertEquals(Phase.FAILED, step.state.phase)
        // Partial response bytes are retained in the account for accounting.
        assertEquals(
            128,
            step.state.callAccounts
                .single()
                .responseBytes,
        )
    }

    @Test
    fun staleCallFailedIsIgnored() {
        val waiting = driveTo(Phase.WAITING_MODEL)
        val step =
            TurnReducer.reduce(waiting, TurnEvent.Model.CallFailed(Fixtures.call(99), 0, Fixtures.error()))
        assertTrue(step.ignored)
        assertEquals(waiting, step.state)
    }

    @Test
    fun protocolErrorTerminalFailsTurn() {
        val receiving = driveTo(Phase.RECEIVING_MODEL)
        val error = Fixtures.error(ErrorCode.INTERNAL, "malformed provider stream")
        val step =
            TurnReducer.reduce(
                receiving,
                TurnEvent.Model.Finished(Fixtures.call(1), 400, ModelTerminal.ProtocolError(error)),
            )
        assertEquals(Phase.FAILED, step.state.phase)
        assertEquals(error, step.state.error)
    }

    @Test
    fun eventsAfterFailureAreIgnored() {
        val failed = driveTo(Phase.FAILED)
        for (event in listOf<TurnEvent>(
            TurnEvent.Lifecycle.TurnResumed,
            TurnEvent.Lifecycle.ContextReady(Fixtures.call(9), 100),
            TurnEvent.Model.StreamStarted(Fixtures.call(9)),
            TurnEvent.Tool.ResultsRecorded,
        )) {
            val step = TurnReducer.reduce(failed, event)
            assertTrue("event $event must be ignored", step.ignored)
            assertEquals(failed, step.state)
        }
    }
}
