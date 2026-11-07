package com.helix.feature.conversation.presentation

import com.helix.core.model.TurnState
import com.helix.feature.conversation.application.ConversationHistoryRequest
import com.helix.feature.conversation.application.ConversationHistorySnapshot
import com.helix.feature.conversation.application.ConversationMessageSnapshot
import com.helix.feature.conversation.application.ConversationMessageVisibility
import com.helix.feature.conversation.application.ConversationScreenRequest
import com.helix.feature.conversation.application.ConversationScreenSnapshot
import com.helix.feature.conversation.application.ConversationSessionSnapshot
import com.helix.feature.conversation.application.ConversationTurnSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ChatScreenProjectionTest {
    private val projection =
        ChatScreenProjection({ it?.name ?: "UNKNOWN" }, { "Cancelled before execution" }, { it.name })

    @Test fun streamFromAnotherTurnCannotBeAttachedToTheLatestPersistedTurn() {
        val before = screen().copy(activeTurn = TurnUi("old", TurnState.RECEIVING_MODEL, "old stream", null, false))
        val read = screenSnapshot(before, ConversationTurnSnapshot("new", "WAITING_MODEL"))
        val projected = projection.applyScreen(read, before, 1)
        assertEquals("new", projected.activeTurn?.id)
        assertEquals(null, projected.activeTurn?.streamingText)
    }

    @Test fun anotherLiveTurnPublishedDuringReadInvalidatesTheEntireSnapshot() {
        val before = screen().copy(activeTurn = TurnUi("old", TurnState.RECEIVING_MODEL, "old stream", null, false))
        val read = screenSnapshot(before, ConversationTurnSnapshot("old", "RECEIVING_MODEL"))
        val current = before.copy(activeTurn = TurnUi("new", TurnState.WAITING_MODEL, null, null, false))
        assertSame(current, projection.applyScreen(read, current, 1))
    }

    @Test fun liveTerminalPublicationCannotBeRolledBackByEarlierPersistedState() {
        val before = screen().copy(activeTurn = TurnUi("turn", TurnState.RECEIVING_MODEL, "old stream", null, false))
        val read = screenSnapshot(before, ConversationTurnSnapshot("turn", "RECEIVING_MODEL"))
        val current = before.copy(activeTurn = TurnUi("turn", TurnState.FAILED, null, "failure", true))
        val projected = projection.applyScreen(read, current, 1)
        assertEquals(current.activeTurn, projected.activeTurn)
    }

    @Test fun currentStreamIsRetainedOnlyForTheSameSessionAndTurn() {
        val before = screen().copy(activeTurn = TurnUi("turn", TurnState.RECEIVING_MODEL, "old", null, false))
        val read = screenSnapshot(before, ConversationTurnSnapshot("turn", "RECEIVING_MODEL"))
        val current = before.copy(activeTurn = before.activeTurn?.copy(streamingText = "new"))
        assertEquals("new", projection.applyScreen(read, current, 1).activeTurn?.streamingText)
    }

    @Test fun missingSessionIsExplicitAndClearsPersistedMetadata() {
        val before = screen()
        val request = ConversationScreenRequest.capture("s", before, 1)
        val missing = ConversationScreenSnapshot(request, ConversationHistorySnapshot(request.history, null), null)
        assertEquals(true, missing.sessionMissing)
        val projected = projection.applyScreen(missing, before, 1)
        assertEquals(null, projected.openSessionId)
        assertEquals("", projected.sessionTitle)
        assertEquals(null, projected.activeTurn)
    }

    @Test fun subscriptionOverlayRequiresTheSameOriginalTurnAndModelCall() {
        val before = screen().copy(subscriptionRecoveries = listOf(SubscriptionRecoveryUi("turn", "old", busy = true)))
        val read =
            screenSnapshot(before, null).copy(
                subscriptionRecoveries =
                    listOf(
                        com.helix.feature.conversation.application.recovery
                            .SubscriptionRecoveryFact("turn", "new", true),
                    ),
            )
        val projected = projection.applyScreen(read, before, 1)
        assertEquals(false, projected.subscriptionRecoveries.single().busy)
        assertEquals(true, projected.subscriptionRecoveries.single().localResultAvailable)
    }

    @Test fun lateHistoryReadCannotReplaceANewerPage() {
        val before = screen()
        val read = snapshot(before)
        val newer = before.copy(historyLimit = 200)
        assertSame(newer, projection.applyHistory(read, newer, 1))
    }

    @Test fun lateHistoryReadCannotReplaceANewerThroughCursorOrFocus() {
        val before = screen()
        val read = snapshot(before)
        val earlier = before.copy(historyThrough = 15, historyFocusRevision = 1, focusedHistoryMessage = "earlier")
        assertSame(earlier, projection.applyHistory(read, earlier, 1))
        val refocused = before.copy(historyFocusRevision = 2, focusedHistoryMessage = "newer-focus")
        assertSame(refocused, projection.applyHistory(read, refocused, 1))
    }

    @Test fun navigationGenerationRejectsReturnToAnIdenticalSessionAndWindow() {
        val before = screen()
        val read = snapshot(before)
        assertSame(before, projection.applyHistory(read, before, 3))
    }

    @Test fun anotherSessionCannotConsumeTheRead() {
        val before = screen()
        val read = snapshot(before)
        val another = before.copy(openSessionId = "another")
        assertSame(another, projection.applyHistory(read, another, 1))
    }

    @Test fun currentTransientFailureAndStreamingAreNotOverwrittenByOlderRead() {
        val before = screen()
        val read = snapshot(before)
        val latest =
            before.copy(
                blockedReason = "Attachment changed",
                blockedReasonEpoch = 9,
                activeTurn = TurnUi("live", TurnState.RECEIVING_MODEL, "new streaming text", null, false),
                recoveryBusy = setOf("live:QUERY_RESULT"),
            )
        val merged = projection.applyHistory(read, latest, 1)
        assertEquals(listOf("message"), merged.messages.map { it.id })
        assertEquals(latest.blockedReason, merged.blockedReason)
        assertEquals(latest.blockedReasonEpoch, merged.blockedReasonEpoch)
        assertEquals(latest.activeTurn, merged.activeTurn)
        assertEquals(latest.recoveryBusy, merged.recoveryBusy)
    }

    @Test fun newlyPublishedTurnControlIsNotDroppedByAnOlderOwnershipSnapshot() {
        val before = screen()
        val read = snapshot(before)
        val latest =
            before.copy(
                toolTimeline = listOf(ToolTimelineRow("live", "call", "read", "{}", "pending", null, null)),
            )
        assertSame(latest, projection.applyHistory(read, latest, 1))
    }

    @Test fun closingHistoryRetainsTheLiveApprovalOverlay() {
        val before =
            screen().copy(
                toolTimeline = listOf(ToolTimelineRow("live", "call", "read", "{}", "pending", null, null)),
            )
        val read = ConversationHistorySnapshot(ConversationHistoryRequest.capture(null, before, 1), null)
        val merged = projection.applyHistory(read, before, 1)
        assertEquals(null, merged.openSessionId)
        assertEquals(before.toolTimeline, merged.toolTimeline)
    }

    @Test fun hiddenProtocolNeverBecomesConversationText() {
        val before = screen()
        val visible = snapshot(before)
        val protocol =
            visible.messages.single().copy(
                presentation =
                    MessagePresentationFact(
                        "protocol",
                        "turn",
                        1,
                        "ASSISTANT",
                        PresentationMessageKind.TOOL_CALLS,
                        false,
                    ),
                visibility = ConversationMessageVisibility.HIDDEN,
                content = "tool wire protocol",
            )
        val projected = projection.messagesFor(visible.copy(messages = visible.messages + protocol))
        assertEquals(listOf("message"), projected.map { it.id })
    }

    @Test fun corruptTurnStateFailsClosedAndTerminalTextDoesNotLeak() {
        val projected = projection.turnUiFor(ConversationTurnSnapshot("turn", "CORRUPT"), "old stream")
        assertEquals(TurnState.FAILED, projected.state)
        assertEquals(null, projected.streamingText)
        assertEquals(true, projected.retryable)
    }

    private fun snapshot(current: ChatScreenState) =
        ConversationHistorySnapshot(
            request = ConversationHistoryRequest.capture("s", current, 1),
            sessionId = "s",
            messages =
                listOf(
                    ConversationMessageSnapshot(
                        MessagePresentationFact("message", "turn", 0, "USER", PresentationMessageKind.TEXT, false),
                        ConversationMessageVisibility.CONVERSATION,
                        "Hello",
                    ),
                ),
        )

    private fun screenSnapshot(
        current: ChatScreenState,
        turn: ConversationTurnSnapshot?,
    ): ConversationScreenSnapshot {
        val request = ConversationScreenRequest.capture("s", current, 1)
        return ConversationScreenSnapshot(
            request,
            snapshot(current),
            ConversationSessionSnapshot(
                "s",
                "Session",
                null,
                null,
                false,
                false,
                null,
                null,
                SessionModelSelectionSnapshot(null, null, com.helix.core.model.ReasoningEffort.OFF),
                ChatContextUsage(),
                emptyList(),
                false,
                false,
            ),
            turn,
            listOfNotNull(turn),
        )
    }

    private fun screen() =
        ChatScreenState(
            sessions = emptyList(),
            openSessionId = "s",
            selectedModel = null,
            messages = emptyList(),
            toolTimeline = emptyList(),
            activeTurn = null,
            pendingDisclosure = null,
            blockedReason = null,
            retryTargetTurnId = null,
        )
}
