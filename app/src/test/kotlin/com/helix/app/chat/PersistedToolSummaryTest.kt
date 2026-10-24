package com.helix.app.chat

import com.helix.core.model.ToolCallState
import org.junit.Assert.assertEquals
import org.junit.Test

class PersistedToolSummaryTest {
    @Test fun cancelledBeforeStartIsLocalizedWhenReopeningHistory() {
        assertEquals("本地化取消", persistedToolSummary("CANCELLED", "Stable business description") { "本地化取消" })
    }

    @Test fun unknownAndAfterStartOutcomesNeverClaimCancellationBeforeExecution() {
        listOf(ToolCallState.NEEDS_REVIEW, ToolCallState.INTERRUPTED, ToolCallState.FAILED).forEach { state ->
            assertEquals(
                "Original execution diagnostics",
                persistedToolSummary(state.name, "Original execution diagnostics") {
                    error("Uncertain or failed execution must not be relabeled as never executed")
                },
            )
        }
    }
}
