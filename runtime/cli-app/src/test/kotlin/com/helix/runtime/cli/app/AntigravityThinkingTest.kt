package com.helix.runtime.cli.app

import com.helix.core.model.ReasoningEffort
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AntigravityThinkingTest {
    @Test fun explicitEffortsUseInternalWireBudgetsRatherThanPublicGeminiLevels() {
        assertEquals(
            1024,
            antigravityThinking("claude-fixture", ReasoningEffort.HIGH, 4096)
                .getValue("thinkingBudget")
                .jsonPrimitive.int,
        )
        assertEquals(
            -1,
            antigravityThinking("gemini-3-pro", ReasoningEffort.HIGH, null)
                .getValue("thinkingBudget")
                .jsonPrimitive.int,
        )
    }

    @Test fun unsupportedEffortAndInsufficientOutputBudgetAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            antigravityThinking("claude-fixture", ReasoningEffort.LOW, 4096)
        }
        assertThrows(IllegalArgumentException::class.java) {
            antigravityThinking("claude-fixture", ReasoningEffort.HIGH, 512)
        }
        assertThrows(IllegalArgumentException::class.java) {
            antigravityThinking("unknown-family", ReasoningEffort.HIGH, null)
        }
    }
}
