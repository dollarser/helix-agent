package com.helix.app.chat

import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.TurnBudgets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnBudgetTrackerTest {
    private fun request(text: String = "hello") = ModelRequest("model", listOf(ModelMessage(ModelRole.USER, text)))

    @Test
    fun modelCallLimitFailsBeforeAnExtraProviderCall() {
        val tracker = TurnBudgetTracker(TurnBudgets(2, 1, 100, 100, 200))
        val request = request()
        assertEquals(TurnBudgetTracker.BeginDecision.ALLOWED, tracker.beginCall(request))
        assertEquals(TurnBudgetTracker.BeginDecision.MODEL_CALL_LIMIT, tracker.beginCall(request))
    }

    @Test
    fun unknownUsageUsesNonZeroByteEstimateAndAccumulates() {
        val tracker = TurnBudgetTracker(TurnBudgets(2, 2, 100, 100, 2))
        val request = request("1234")
        assertEquals(TurnBudgetTracker.BeginDecision.ALLOWED, tracker.beginCall(request))
        val stream = ModelStreamState().also { it.apply(ModelEvent.TextDelta("1234")) }
        assertTrue(tracker.finishCall("call-1", request, stream))
        assertEquals(TurnBudgetTracker.BeginDecision.TOKEN_LIMIT, tracker.beginCall(request))
    }

    @Test
    fun reportedUsageCannotOverflowOrBypassOutputAndTotalLimits() {
        val outputTracker = TurnBudgetTracker(TurnBudgets(2, 1, 1_000, 5, 1_000))
        val request = request()
        outputTracker.beginCall(request)
        val output = ModelStreamState().also { it.apply(ModelEvent.Usage(1, 6)) }
        assertFalse(outputTracker.finishCall("call-1", request, output))

        val totalTracker = TurnBudgetTracker(TurnBudgets(2, 1, 1_000, 1_000, 10))
        totalTracker.beginCall(request)
        val huge = ModelStreamState().also { it.apply(ModelEvent.Usage(Long.MAX_VALUE, Long.MAX_VALUE)) }
        assertFalse(totalTracker.finishCall("call-2", request, huge))
    }
}
