package com.helix.app.agent

import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.TurnBudgets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryOutputBudgetTest {
    @Test fun summaryTargetDoesNotRejectValidReasoningUsage() {
        val summary = SummaryOutputBudget.forRequest(17058, 4096, 128000)
        assertEquals(2132L, summary.target)
        assertEquals(4096L, summary.allowance)
        val tracker = TurnBudgetTracker(TurnBudgets(32, 33, 128000, 4096, 160000))
        val request =
            ModelRequest("model", listOf(ModelMessage(ModelRole.USER, "summary")), maxOutputTokens = summary.allowance)
        val admitted = requireNotNull(tracker.prepareCall(request).request)
        val stream = ModelStreamState().also { it.apply(ModelEvent.Usage(10890, 2351)) }
        assertTrue(tracker.finishCall("summary", admitted, stream))
    }

    @Test fun reasoningModelsHaveHeadroomWithoutExpandingSummaryProse() {
        assertEquals(SummaryOutputBudget(500, 16384), SummaryOutputBudget.forRequest(4000, 32768, 262144))
        assertEquals(SummaryOutputBudget(500, 8192), SummaryOutputBudget.forRequest(4000, 8192, 262144))
        assertEquals(SummaryOutputBudget(128, 8192), SummaryOutputBudget.forRequest(800, 8192, 262144))
        assertEquals(SummaryOutputBudget(4096, 16384), SummaryOutputBudget.forRequest(100000, 32768, 262144))
    }

    @Test fun explicitUserBudgetAndSmallWindowRemainHardBounds() {
        assertEquals(SummaryOutputBudget(512, 512), SummaryOutputBudget.forRequest(20000, 512, 128000))
        assertEquals(SummaryOutputBudget(256, 256), SummaryOutputBudget.forRequest(20000, 4096, 1024))
    }
}
