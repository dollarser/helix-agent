package com.helix.app.ui

import com.helix.core.model.ToolCallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ToolStateToneTest {
    @Test fun uncertainEffectIsVisuallyDistinctFromFailureAndCancellation() {
        assertNotEquals(toolStateIcon(ToolCallState.FAILED), toolStateIcon(ToolCallState.NEEDS_REVIEW))
        assertNotEquals(toolStateIcon(ToolCallState.CANCELLED), toolStateIcon(ToolCallState.NEEDS_REVIEW))
        assertEquals(ToolStateTone.REVIEW, toolStateTone(ToolCallState.INTERRUPTED))
    }
}
