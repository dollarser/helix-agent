package com.helix.extensions.mobileuse.tools

import com.helix.extensions.mobileuse.automation.AutomationActionResult
import com.helix.extensions.mobileuse.automation.AutomationActionStatus
import com.helix.tools.framework.ToolExecutorResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationToolOutcomeTest {
    @Test fun unsupportedActionExplainsRecoveryWithoutClaimingAFocusCause() {
        val result =
            AutomationActionResult(AutomationActionStatus.ACTION_NOT_SUPPORTED).toToolOutcome()
                as ToolExecutorResult.Failed
        assertTrue(result.detail.startsWith("ACTION_NOT_SUPPORTED:"))
        assertTrue(result.detail.contains("ui.snapshot"))
        assertTrue(result.detail.contains("scrollable container"))
        assertFalse(result.detail.contains("focus"))
        assertTrue(result.sideEffectFree)
        assertFalse(result.requiresReview)
    }

    @Test fun uncertainActionsKeepReviewAndNeverSuggestReplay() {
        listOf(AutomationActionStatus.ACTION_FAILED, AutomationActionStatus.ACTION_OUTCOME_UNKNOWN).forEach { status ->
            val result = AutomationActionResult(status).toToolOutcome() as ToolExecutorResult.Failed
            assertEquals(status.name, result.detail)
            assertFalse(result.sideEffectFree)
            assertTrue(result.requiresReview)
        }
    }
}
