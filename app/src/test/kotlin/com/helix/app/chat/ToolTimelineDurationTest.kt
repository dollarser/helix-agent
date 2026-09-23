package com.helix.app.chat

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolTimelineDurationTest {
    private fun createTimeline(): Pair<MutableStateFlow<ChatScreenState>, ChatToolTimeline> {
        val screenState =
            MutableStateFlow(
                ChatScreenState(
                    sessions = emptyList(),
                    openSessionId = "session-1",
                    badge = null,
                    messages = emptyList(),
                    toolTimeline = emptyList(),
                    activeTurn = null,
                    pendingDisclosure = null,
                    blockedReason = null,
                    retryTargetTurnId = null,
                ),
            )
        return screenState to ChatToolTimeline(screenState) { _, _ -> "test" }
    }

    @Test
    fun defaultDurationIsNull() {
        val row =
            ToolTimelineRow(
                turnId = "turn-1",
                callId = "call-1",
                toolName = "files.read",
                requestSummary = "{}",
                stateLabel = "Completed",
                resultSummary = "content",
                card = null,
            )
        assertNull(row.durationMs)
    }

    @Test
    fun publishToolRowStoresDuration() {
        val (screenState, timeline) = createTimeline()

        timeline.publishToolRow(
            turnId = "turn-1",
            callId = "call-1",
            toolName = "files.read",
            requestSummary = "{}",
            stateLabel = "Running",
            resultSummary = null,
            card = null,
            durationMs = null,
        )
        assertNull(
            screenState.value.toolTimeline
                .single()
                .durationMs,
        )

        timeline.publishToolRow(
            turnId = "turn-1",
            callId = "call-1",
            toolName = "files.read",
            requestSummary = "{}",
            stateLabel = "Completed",
            resultSummary = "ok",
            card = null,
            durationMs = 125L,
        )
        assertEquals(
            125L,
            screenState.value.toolTimeline
                .single()
                .durationMs,
        )
    }

    @Test
    fun publishToolRowPreservesPreviousDuration() {
        val (screenState, timeline) = createTimeline()

        timeline.publishToolRow(
            turnId = "turn-1",
            callId = "call-1",
            toolName = "files.read",
            requestSummary = "{}",
            stateLabel = "Completed",
            resultSummary = "ok",
            card = null,
            durationMs = 250L,
        )
        assertEquals(
            250L,
            screenState.value.toolTimeline
                .single()
                .durationMs,
        )

        // Subsequent update without duration preserves previously set duration
        timeline.publishToolRow(
            turnId = "turn-1",
            callId = "call-1",
            toolName = "files.read",
            requestSummary = "{}",
            stateLabel = "Completed",
            resultSummary = "ok",
            card = null,
            durationMs = null,
        )
        assertEquals(
            250L,
            screenState.value.toolTimeline
                .single()
                .durationMs,
        )
    }
}
