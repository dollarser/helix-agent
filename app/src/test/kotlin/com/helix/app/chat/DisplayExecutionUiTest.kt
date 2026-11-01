package com.helix.app.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayExecutionUiTest {
    @Test fun olderRouteReleaseCannotOverwriteANewerScopeOnTheSameTurn() {
        val current = DisplayExecutionUi("session", "turn", DisplayExecutionState.ACTIVE, generation = 2)
        val stale = current.copy(state = DisplayExecutionState.RELEASED, generation = 1)
        assertEquals(listOf(current), mergeDisplayExecution(listOf(current), stale))
        val released = current.copy(state = DisplayExecutionState.RELEASED)
        assertEquals(listOf(released), mergeDisplayExecution(listOf(current), released))
        val other = released.copy(sessionId = "other")
        assertEquals(listOf(current, other), mergeDisplayExecution(listOf(current), other))
    }
}
