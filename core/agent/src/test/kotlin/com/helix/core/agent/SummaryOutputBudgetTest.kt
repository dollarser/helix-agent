package com.helix.core.agent

import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.TurnBudgets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryOutputBudgetTest {
    private val request =
        ModelRequest(
            "model",
            listOf(ModelMessage(ModelRole.SYSTEM, "summarize"), ModelMessage(ModelRole.USER, "history")),
        )

    @Test fun allowanceUsesCompleteCalibratedInputAndSafetyReserve() {
        val input = ModelInputEstimate.of(request).total
        val bound = SummaryOutputBudget.bound(request, 131072, 1.5)
        assertEquals(
            131072L - kotlin.math.ceil(input * 1.5).toLong() - SummaryOutputBudget.reserve(131072),
            bound.maxOutputTokens,
        )
        assertTrue(requireNotNull(bound.maxOutputTokens) > 16384L)
        val focused = request.copy(messages = request.messages + ModelMessage(ModelRole.USER, "focus ".repeat(100)))
        assertTrue(
            requireNotNull(SummaryOutputBudget.bound(focused, 131072, 1.5).maxOutputTokens) <
                requireNotNull(bound.maxOutputTokens),
        )
    }

    @Test fun safetyMarginScalesWithoutConsumingSmallWindows() {
        assertEquals(256L, SummaryOutputBudget.reserve(1024))
        assertEquals(1024L, SummaryOutputBudget.reserve(4096))
        assertEquals(2048L, SummaryOutputBudget.reserve(8192))
        assertEquals(9831L, SummaryOutputBudget.reserve(98304))
        assertEquals(13108L, SummaryOutputBudget.reserve(131072))
        // Recorded real-model estimate 57457, measured input 61332: the new margin covers this gap.
        val allowance = 98304L - 57457L - SummaryOutputBudget.reserve(98304)
        assertEquals(31016L, allowance)
        assertTrue(61332L + allowance < 98304L)
    }

    @Test fun largeFocusAndCalibratedInputRemainWithinTheSameReserve() {
        val focused = request.copy(messages = request.messages + ModelMessage(ModelRole.USER, "重点😀".repeat(4000)))
        val scale = 1.5
        val input = kotlin.math.ceil(ModelInputEstimate.of(focused).total * scale).toLong()
        val bounded = SummaryOutputBudget.bound(focused, 131072, scale)
        assertEquals(131072L - input - SummaryOutputBudget.reserve(131072), bounded.maxOutputTokens)
        assertEquals(512L, SummaryOutputBudget.bound(focused, 131072, scale, 512).maxOutputTokens)
    }

    @Test fun declaredServiceCapClampsRemainingWindow() {
        assertEquals(8192L, SummaryOutputBudget.bound(request, 131072, serverOutputLimit = 8192L).maxOutputTokens)
    }

    @Test(expected = ContextCapacityException::class)
    fun noRemainingSpaceFailsWithoutClippingInput() {
        SummaryOutputBudget.bound(request, 32L)
    }

    @Test fun summaryUsageHasDedicatedBoundButOrdinaryCallsDoNotExpand() {
        val tracker = TurnBudgetTracker(TurnBudgets(maxOutputTokens = 4096))
        val bound = SummaryOutputBudget.bound(request, 131072, serverOutputLimit = 20000L)
        val admitted = requireNotNull(tracker.prepareCall(bound, summary = true).request)
        assertEquals(20000L, admitted.maxOutputTokens)
        val stream = ModelStreamState().also { it.apply(ModelEvent.Usage(100, 18000)) }
        assertTrue(tracker.finishCall("summary", admitted, stream, summary = true))
        assertFalse(tracker.finishCall("ordinary", admitted, stream))
        assertEquals(4096L, tracker.prepareCall(bound).request?.maxOutputTokens)
        val over = ModelStreamState().also { it.apply(ModelEvent.Usage(100, 20001)) }
        assertFalse(tracker.finishCall("over", admitted, over, summary = true))
    }
}
