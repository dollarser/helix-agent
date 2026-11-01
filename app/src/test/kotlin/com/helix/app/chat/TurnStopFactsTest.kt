package com.helix.app.chat

import com.helix.core.model.ToolCallState
import org.junit.Assert.assertEquals
import org.junit.Test

class TurnStopFactsTest {
    @Test fun onlySuccessfulVerifiedToolResultsCountAsVerifiedEffects() {
        assertEquals(StopEffect.VERIFIED, stopEffect(ToolCallState.COMPLETED, "SUCCEEDED", true, false))
        assertEquals(StopEffect.RECORDED, stopEffect(ToolCallState.COMPLETED, "SUCCEEDED", false, false))
        assertEquals(StopEffect.UNKNOWN, stopEffect(ToolCallState.COMPLETED, "UNKNOWN", true, false))
    }

    @Test fun cancellationAndFailureNeverImplyRollback() {
        val states =
            listOf(
                ToolCallState.CANCELLED,
                ToolCallState.FAILED,
                ToolCallState.INTERRUPTED,
                ToolCallState.NEEDS_REVIEW,
            )
        for (state in states) {
            assertEquals(StopEffect.UNKNOWN, stopEffect(state, null, false, false))
        }
        assertEquals(StopEffect.NOT_STARTED, stopEffect(ToolCallState.CANCELLED, null, false, true))
        assertEquals(StopEffect.NOT_STARTED, stopEffect(ToolCallState.AWAITING_APPROVAL, null, false, false))
    }

    @Test fun continuationRetainsOriginalIdsAndWarnsAgainstReplay() {
        val facts = TurnStopFacts(listOf(StopActionFact("original", "ui.click", StopEffect.UNKNOWN)), 2)
        assertEquals(true, facts.continuationContext().contains("original ui.click: UNKNOWN"))
        assertEquals(true, facts.continuationContext().contains("never replayed blindly"))
    }

    @Test fun missingOrMalformedEvidenceNeverProvesNotStarted() {
        assertEquals(false, auditProvesNotStarted(null))
        assertEquals(false, auditProvesNotStarted("broken"))
        assertEquals(false, auditProvesNotStarted("[]"))
        assertEquals(false, auditProvesNotStarted("{}"))
        assertEquals(false, auditProvesNotStarted("{\"executionStartedAt\":12}"))
        assertEquals(true, auditProvesNotStarted("{\"executionStartedAt\":null}"))
        assertEquals(StopEffect.UNKNOWN, stopEffect(null, null, false, false))
    }
}
