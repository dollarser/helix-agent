package com.helix.feature.conversation.presentation

import com.helix.core.model.ToolCallState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatToolDisplayTest {
    private val call = ToolDisplayCall("session", "turn", "call", "read", "{}", "Inspect synthetic fixture")

    private fun screen() =
        MutableStateFlow(
            ChatScreenState(emptyList(), "session", null, emptyList(), emptyList(), null, null, null, null),
        )

    @Test fun navigationDuringPositionReadCannotPublishIntoAnotherSession() {
        val screen = screen()
        val presentation =
            ChatToolPresentation(screen, { _, _ -> "label" }, {}, { _, _ ->
                screen.value = screen.value.copy(openSessionId = "other")
                ToolTimelinePosition(4, 2)
            })
        presentation.publish(ToolDisplayEvent.Prepared(call))
        assertEquals("other", screen.value.openSessionId)
        assertTrue(screen.value.toolTimeline.isEmpty())
    }

    @Test fun preparationAndSettlementPreserveCallIdentityPositionDurationAndIntent() {
        val screen = screen()
        val presentation =
            ChatToolPresentation(screen, { id, _ -> "label-$id" }, {}, { _, _ -> ToolTimelinePosition(4, 2) })
        presentation.publish(ToolDisplayEvent.Prepared(call))
        presentation.publish(ToolDisplayEvent.Running(call))
        presentation.publish(settled(call))
        val row = screen.value.toolTimeline.single()
        assertEquals("call", row.callId)
        assertEquals(ToolTimelinePosition(4, 2), row.position)
        assertEquals(call.modelIntent, row.modelIntent)
        assertEquals(ToolCallState.COMPLETED, row.state)
        assertEquals(42L, row.durationMs)
        assertEquals("synthetic result", row.resultSummary)
    }

    @Test fun factsForAnotherSessionCannotRewriteTheCurrentTimeline() {
        val screen = screen()
        val presentation = ChatToolPresentation(screen, { _, _ -> "label" }, {}, { _, _ -> null })
        val other = call.copy(sessionId = "other")
        presentation.publish(ToolDisplayEvent.Prepared(other))
        presentation.publish(ToolDisplayEvent.Running(other))
        presentation.publish(settled(other))
        assertTrue(screen.value.toolTimeline.isEmpty())
    }

    private fun settled(value: ToolDisplayCall) =
        ToolDisplayEvent.Settled(
            value,
            ToolCallState.COMPLETED,
            "synthetic result",
            42,
            false,
            null,
            null,
            null,
            emptyList(),
        )
}
