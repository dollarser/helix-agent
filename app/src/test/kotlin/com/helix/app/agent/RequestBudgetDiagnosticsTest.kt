package com.helix.app.agent

import com.helix.app.runcontrol.TurnBudgetBounds
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRole
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.TurnBudgets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestBudgetDiagnosticsTest {
    private val context =
        ChatContextRequest(
            "model",
            listOf(
                ModelMessage(ModelRole.SYSTEM, "private instructions"),
                ModelMessage(ModelRole.USER, "private history"),
            ),
            emptyList(),
            100,
            ReasoningEffort.OFF,
        )

    @Test fun allAdmissionEstimatesAgreeAndDiagnosticsContainNoBodies() {
        val request = context.modelRequest()
        val estimate = ModelInputEstimate.of(request)
        assertEquals(context.inputTokens(), estimate.total)
        assertTrue(estimate.instructions > 0 && estimate.history > 0)
        val exact = TurnBudgets(1, 1, estimate.total, 10, estimate.total + 10)
        val tracker = TurnBudgetTracker(exact)
        assertEquals(10L, tracker.prepareCall(request).request?.maxOutputTokens)
        val json = RequestBudgetDiagnostics.request(context, exact, 200_000, tracker)
        assertTrue(json.length <= 512)
        assertFalse(json.contains("private"))
        assertEquals(
            estimate.total,
            Json
                .parseToJsonElement(json)
                .jsonObject
                .getValue("input")
                .jsonPrimitive.long,
        )
        val smaller = TurnBudgetTracker(exact.copy(maxInputTokens = estimate.total - 1))
        assertEquals(TurnBudgetTracker.BeginDecision.INPUT_LIMIT, smaller.prepareCall(request).decision)
        assertEquals(0, smaller.consumedCalls)
    }

    @Test fun actualLimitDimensionsStayDistinctAndUsageNeverWraps() {
        val request = context.modelRequest()
        for ((usage, code) in listOf(
            (100L to 1L) to "INPUT_TOKEN_LIMIT",
            (1L to 11L) to "OUTPUT_TOKEN_LIMIT",
            (Long.MAX_VALUE to 1L) to "TURN_TOTAL_TOKEN_LIMIT",
        )) {
            val tracker = TurnBudgetTracker(TurnBudgets(1, 1, 80, 10, 1000))
            val stream =
                ModelStreamState().also {
                    it.apply(
                        com.helix.core.model.ModelEvent
                            .Usage(usage.first, usage.second),
                    )
                }
            assertFalse(tracker.finishCall("call", request, stream))
            assertEquals(code, tracker.lastFailureCode)
            assertTrue(tracker.consumedTokens > 0)
        }
    }

    @Test fun defaultSnapshotIsBoundedAndUnknownUsageChargesTheSameInput() {
        val tracker = TurnBudgetTracker(TurnBudgetBounds.DEFAULT)
        assertTrue(
            RequestBudgetDiagnostics.request(context, TurnBudgetBounds.DEFAULT, 1_000_000, tracker).length <= 512,
        )
        assertEquals(
            context.inputTokens(),
            ModelCallUsage.account("call", context.modelRequest(), ModelStreamState()).effectiveInput,
        )
    }
}
