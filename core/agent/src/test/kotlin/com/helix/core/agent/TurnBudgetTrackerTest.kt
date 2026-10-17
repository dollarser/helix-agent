package com.helix.core.agent

import com.helix.core.agent.ModelStreamState
import com.helix.core.agent.TurnBudgetTracker
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.TurnBudgets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnBudgetTrackerTest {
    private fun request(text: String = "hello") = ModelRequest("model", listOf(ModelMessage(ModelRole.USER, text)))

    @Test
    fun longTaskDefaultAdmitsAndSettles1024CallsWithoutRefundingOnRestore() {
        val budgets = com.helix.core.agent.TurnBudgetBounds.DEFAULT
        var tracker = TurnBudgetTracker(budgets)
        repeat(1024) { index ->
            if (index ==
                512
            ) {
                tracker = TurnBudgetTracker.restore(budgets, tracker.consumedCalls, tracker.consumedTokens)
            }
            val admitted = tracker.prepareCall(request())
            assertEquals(TurnBudgetTracker.BeginDecision.ALLOWED, admitted.decision)
            val stream = ModelStreamState().also { it.apply(ModelEvent.Usage(20, 1)) }
            assertTrue(tracker.finishCall("call-$index", requireNotNull(admitted.request), stream))
        }
        assertEquals(1024, tracker.consumedCalls)
        assertEquals(21504L, tracker.consumedTokens)
        assertEquals(TurnBudgetTracker.BeginDecision.ALLOWED, tracker.prepareCall(request()).decision)
    }

    @Test
    fun historicalModelCallLimitDoesNotBlockAnotherProviderCall() {
        val tracker = TurnBudgetTracker(TurnBudgets(maxOutputTokens = 100))
        val request = request()
        assertEquals(TurnBudgetTracker.BeginDecision.ALLOWED, tracker.prepareCall(request).decision)
        assertEquals(TurnBudgetTracker.BeginDecision.ALLOWED, tracker.prepareCall(request).decision)
    }

    @Test
    fun unknownUsageUsesNonZeroByteEstimateAndAccumulates() {
        val tracker = TurnBudgetTracker(TurnBudgets(maxOutputTokens = 100))
        val request = request("1234")
        assertEquals(TurnBudgetTracker.BeginDecision.ALLOWED, tracker.prepareCall(request).decision)
        val stream = ModelStreamState().also { it.apply(ModelEvent.TextDelta("1234")) }
        assertTrue(tracker.finishCall("call-1", request, stream))
        assertEquals(TurnBudgetTracker.BeginDecision.ALLOWED, tracker.prepareCall(request).decision)
    }

    @Test
    fun outputIsIndependentOfAccumulatedInputUsage() {
        val tracker = TurnBudgetTracker(TurnBudgets(maxOutputTokens = 100))
        val first = requireNotNull(tracker.prepareCall(request("1234").copy(maxOutputTokens = 100)).request)
        assertEquals(100L, first.maxOutputTokens)
        val stream = ModelStreamState().also { it.apply(ModelEvent.Usage(1, 10)) }
        assertTrue(tracker.finishCall("first", first, stream))
        val second = requireNotNull(tracker.prepareCall(request("1234")).request)
        assertEquals(100L, second.maxOutputTokens)
    }

    @Test
    fun smallerExplicitOutputLimitIsPreserved() {
        val tracker = TurnBudgetTracker(TurnBudgets(maxOutputTokens = 10))
        assertEquals(3L, tracker.prepareCall(request().copy(maxOutputTokens = 3)).request?.maxOutputTokens)
    }

    @Test
    fun historicalTotalLimitDoesNotRejectNewRequest() {
        val tracker = TurnBudgetTracker(TurnBudgets(maxOutputTokens = 100))
        val refused = tracker.prepareCall(request("12345678"))
        assertEquals(TurnBudgetTracker.BeginDecision.ALLOWED, refused.decision)
        assertEquals(100L, refused.request?.maxOutputTokens)
        assertEquals(TurnBudgetTracker.BeginDecision.ALLOWED, tracker.prepareCall(request("1234")).decision)
    }

    @Test
    fun providerUsageAboveTheBoundRequestStillFails() {
        val tracker = TurnBudgetTracker(TurnBudgets(maxOutputTokens = 100))
        val bound = requireNotNull(tracker.prepareCall(request().copy(maxOutputTokens = 3)).request)
        val stream = ModelStreamState().also { it.apply(ModelEvent.Usage(1, 4)) }
        assertFalse(tracker.finishCall("call", bound, stream))
    }

    @Test
    fun restoredTrackerDoesNotRefundModelCallsOrTokens() {
        val budgets = TurnBudgets(maxOutputTokens = 100)
        val tracker = TurnBudgetTracker.restore(budgets, consumedModelCalls = 2, consumedTokens = 10)

        assertEquals(2, tracker.consumedCalls)
        assertEquals(10L, tracker.consumedTokens)
        assertEquals(TurnBudgetTracker.BeginDecision.ALLOWED, tracker.prepareCall(request("1")).decision)
        assertEquals(3, tracker.consumedCalls)
        assertEquals(TurnBudgetTracker.BeginDecision.ALLOWED, tracker.prepareCall(request()).decision)

        val tokenBound = TurnBudgetTracker.restore(budgets, consumedModelCalls = 0, consumedTokens = 40)
        assertEquals(TurnBudgetTracker.BeginDecision.ALLOWED, tokenBound.prepareCall(request("1")).decision)
        assertEquals(1, tokenBound.consumedCalls)
    }

    @Test
    fun restoredTrackerAcceptsUsageBeyondHistoricalLimitsAndRejectsNegativeUsage() {
        val budgets = TurnBudgets(maxOutputTokens = 100)

        assertEquals(4, TurnBudgetTracker.restore(budgets, 4, 51).consumedCalls)
        assertEquals(51L, TurnBudgetTracker.restore(budgets, 4, 51).consumedTokens)
        assertThrows(IllegalArgumentException::class.java) { TurnBudgetTracker.restore(budgets, -1, 0) }
        assertThrows(IllegalArgumentException::class.java) { TurnBudgetTracker.restore(budgets, 0, -1) }
    }

    @Test
    fun reportedUsageCannotOverflowOrBypassOutputAndTotalLimits() {
        val outputTracker = TurnBudgetTracker(TurnBudgets(maxOutputTokens = 5))
        val request = request()
        outputTracker.prepareCall(request).decision
        val output = ModelStreamState().also { it.apply(ModelEvent.Usage(1, 6)) }
        assertFalse(outputTracker.finishCall("call-1", request, output))

        val totalTracker = TurnBudgetTracker(TurnBudgets(maxOutputTokens = 1_000))
        totalTracker.prepareCall(request).decision
        val huge = ModelStreamState().also { it.apply(ModelEvent.Usage(Long.MAX_VALUE, Long.MAX_VALUE)) }
        assertFalse(totalTracker.finishCall("call-2", request, huge))
    }
}
