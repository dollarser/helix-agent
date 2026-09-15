package com.helix.app.agent

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
        assertEquals(TurnBudgetTracker.BeginDecision.ALLOWED, tracker.prepareCall(request).decision)
        assertEquals(TurnBudgetTracker.BeginDecision.MODEL_CALL_LIMIT, tracker.prepareCall(request).decision)
    }

    @Test
    fun unknownUsageUsesNonZeroByteEstimateAndAccumulates() {
        val tracker = TurnBudgetTracker(TurnBudgets(2, 2, 100, 100, 2))
        val request = request("1234")
        assertEquals(TurnBudgetTracker.BeginDecision.ALLOWED, tracker.prepareCall(request).decision)
        val stream = ModelStreamState().also { it.apply(ModelEvent.TextDelta("1234")) }
        assertTrue(tracker.finishCall("call-1", request, stream))
        assertEquals(TurnBudgetTracker.BeginDecision.TOKEN_LIMIT, tracker.prepareCall(request).decision)
    }

    @Test
    fun outputIsBoundByInputEstimateAndRemainingTotalBeforeTransport() {
        val tracker = TurnBudgetTracker(TurnBudgets(2, 3, 100, 100, 20))
        val first = requireNotNull(tracker.prepareCall(request("1234").copy(maxOutputTokens = 100)).request)
        assertEquals(19L, first.maxOutputTokens)
        val stream = ModelStreamState().also { it.apply(ModelEvent.Usage(1, 10)) }
        assertTrue(tracker.finishCall("first", first, stream))
        val second = requireNotNull(tracker.prepareCall(request("1234")).request)
        assertEquals(8L, second.maxOutputTokens)
    }

    @Test
    fun smallerExplicitOutputLimitIsPreserved() {
        val tracker = TurnBudgetTracker(TurnBudgets(2, 2, 100, 10, 100))
        assertEquals(3L, tracker.prepareCall(request().copy(maxOutputTokens = 3)).request?.maxOutputTokens)
    }

    @Test
    fun noOutputHeadroomRejectsWithoutSpendingTheModelCall() {
        val tracker = TurnBudgetTracker(TurnBudgets(2, 1, 100, 100, 2))
        val refused = tracker.prepareCall(request("12345678"))
        assertEquals(TurnBudgetTracker.BeginDecision.TOKEN_LIMIT, refused.decision)
        assertEquals(null, refused.request)
        assertEquals(TurnBudgetTracker.BeginDecision.ALLOWED, tracker.prepareCall(request("1234")).decision)
    }

    @Test
    fun providerUsageAboveTheBoundRequestStillFails() {
        val tracker = TurnBudgetTracker(TurnBudgets(2, 1, 100, 100, 100))
        val bound = requireNotNull(tracker.prepareCall(request().copy(maxOutputTokens = 3)).request)
        val stream = ModelStreamState().also { it.apply(ModelEvent.Usage(1, 4)) }
        assertFalse(tracker.finishCall("call", bound, stream))
    }

    @Test
    fun reportedUsageCannotOverflowOrBypassOutputAndTotalLimits() {
        val outputTracker = TurnBudgetTracker(TurnBudgets(2, 1, 1_000, 5, 1_000))
        val request = request()
        outputTracker.prepareCall(request).decision
        val output = ModelStreamState().also { it.apply(ModelEvent.Usage(1, 6)) }
        assertFalse(outputTracker.finishCall("call-1", request, output))

        val totalTracker = TurnBudgetTracker(TurnBudgets(2, 1, 1_000, 1_000, 10))
        totalTracker.prepareCall(request).decision
        val huge = ModelStreamState().also { it.apply(ModelEvent.Usage(Long.MAX_VALUE, Long.MAX_VALUE)) }
        assertFalse(totalTracker.finishCall("call-2", request, huge))
    }
}
