package com.helix.core.agent

import com.helix.core.agent.ToolLoopProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolLoopProgressTest {
    @Test fun repeatedEvidenceWarnsBeforeStopping() {
        assertEquals(ToolLoopProgress.Decision.CONTINUE, ToolLoopProgress.evaluate(listOf("a", "a")))
        assertEquals(ToolLoopProgress.Decision.WARN, ToolLoopProgress.evaluate(List(3) { "a" }))
        assertEquals(ToolLoopProgress.Decision.STOP, ToolLoopProgress.evaluate(List(6) { "a" }))
    }

    @Test fun alternatingFailuresAreAlsoBounded() {
        assertEquals(ToolLoopProgress.Decision.WARN, ToolLoopProgress.evaluate(List(6) { "${it % 2}" }))
        assertEquals(ToolLoopProgress.Decision.STOP, ToolLoopProgress.evaluate(List(12) { "${it % 2}" }))
    }

    @Test fun changedResultMutationAndLivePollingBreakTheSequence() {
        for (progress in listOf(null, "new result", "new args", "new version")) {
            assertEquals(
                ToolLoopProgress.Decision.CONTINUE,
                ToolLoopProgress.evaluate(List(5) { "a" } + progress + "a"),
            )
        }
        assertEquals(ToolLoopProgress.Decision.CONTINUE, ToolLoopProgress.evaluate(List(1000) { null }))
    }

    @Test fun reconstructingTheDurableWindowKeepsTheDecision() {
        val history = List(20) { "old-$it" } + List(12) { "${it % 2}" }
        assertEquals(
            ToolLoopProgress.evaluate(history),
            ToolLoopProgress.evaluate(history.takeLast(ToolLoopProgress.WINDOW)),
        )
    }
}
