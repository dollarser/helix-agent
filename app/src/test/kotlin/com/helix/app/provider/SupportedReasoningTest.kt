package com.helix.app.provider

import com.helix.core.model.ReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Test

class SupportedReasoningTest {
    @Test fun summaryLowMustBeSupportedByTheSelectedModel() {
        assertEquals(ReasoningEffort.LOW, supportedReasoning(ReasoningEffort.LOW, listOf(ReasoningEffort.LOW)))
        assertEquals(ReasoningEffort.OFF, supportedReasoning(ReasoningEffort.LOW, listOf(ReasoningEffort.HIGH)))
        assertEquals(ReasoningEffort.OFF, supportedReasoning(ReasoningEffort.LOW, emptyList()))
    }
}
