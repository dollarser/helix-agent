package com.helix.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ToolCallPresentationTest {
    @Test
    fun validSingleLineIntentIsAccepted() {
        assertEquals(
            "Inspect build status",
            ToolCallPresentation("Inspect build status").modelIntent,
        )
    }

    @Test
    fun blankControlMultilineAndOversizedIntentFailClosed() {
        for (value in listOf("", " ", "a\nb", "a\u0001b", "x".repeat(161))) {
            assertThrows(IllegalArgumentException::class.java) {
                ToolCallPresentation(value)
            }
        }
    }
}
