package com.helix.app.chat

import com.helix.app.engine.TurnObservation
import com.helix.app.engine.TurnRuntimeView
import com.helix.core.agent.AttachmentBindingIntent
import com.helix.core.agent.CancelResult
import com.helix.core.agent.RunControlConfig
import com.helix.core.agent.SubmitTurnCommand
import com.helix.core.model.AgentMode
import com.helix.core.model.GoalId
import com.helix.core.model.ProviderId
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.SessionId
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnId
import com.helix.core.model.TurnState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAgentRuntimeTest {
    private val turnId = TurnId("t1")
    private val goalId = GoalId("g1")
    private val budgets = TurnBudgets(8, 9, 128_000, 4_096, 160_000)

    private fun command(
        mode: AgentMode = AgentMode.CHAT,
        text: String? = "hi",
        reasoning: ReasoningEffort = ReasoningEffort.OFF,
        goalId: GoalId? = null,
        attachments: List<AttachmentBindingIntent> = emptyList(),
        clientRequestId: String = "req-1",
    ) = SubmitTurnCommand(
        session = SessionId("s1"),
        providerId = ProviderId("p1"),
        mode = mode,
        text = text,
        budgets = budgets,
        reasoning = reasoning,
        goalId = goalId,
        attachments = attachments,
        clientRequestId = clientRequestId,
    )

    @Test
    fun submitMapsRunControlAndReturnsStartedTurn() {
        val fixture = Fixture()
        val result =
            runBlocking {
                fixture.agent.submit(
                    command(mode = AgentMode.GOAL, text = null, reasoning = ReasoningEffort.MEDIUM, goalId = goalId),
                )
            }

        assertEquals(turnId, result)
        assertEquals(AgentMode.GOAL, fixture.lastControl?.mode)
        assertEquals(budgets, fixture.lastControl?.budgets)
        assertEquals(ReasoningEffort.MEDIUM, fixture.lastControl?.reasoning)
    }

    @Test
    fun submitCarriesAttachmentIntentsAndStableRequestId() {
        val fixture = Fixture()
        val attachment = AttachmentBindingIntent("art-1", "sha-1")

        runBlocking { fixture.agent.submit(command(attachments = listOf(attachment), clientRequestId = "req-stable")) }

        assertEquals(listOf(attachment), fixture.lastCommand?.attachments)
        assertEquals("req-stable", fixture.lastCommand?.clientRequestId)
    }

    @Test
    fun blockedStartSurfacesTurnStartBlocked() {
        val fixture = Fixture().apply { nextStartTurnId = null }

        assertThrows(TurnStartBlocked::class.java) {
            runBlocking { fixture.agent.submit(command()) }
        }
    }

    @Test
    fun repeatedClientRequestIdIsDeduplicatedByCommandSeam() {
        val fixture = Fixture()

        runBlocking {
            assertEquals(turnId, fixture.agent.submit(command(clientRequestId = "same")))
            assertEquals(turnId, fixture.agent.submit(command(clientRequestId = "same")))
        }

        assertEquals(1, fixture.startedTurns.size)
    }

    @Test
    fun cancelUnknownTurnIsNotFoundWithoutCallingCancelSeam() {
        val fixture = Fixture()

        assertEquals(CancelResult.NotFound, runBlocking { fixture.agent.cancel(turnId) })
        assertTrue(fixture.cancelled.isEmpty())
    }

    @Test
    fun cancelTerminalTurnReportsAlreadyTerminal() {
        val fixture = Fixture().apply { phase = TurnState.INTERRUPTED }

        val result = runBlocking { fixture.agent.cancel(turnId) }

        assertEquals(CancelResult.AlreadyTerminal(TurnState.INTERRUPTED), result)
        assertEquals(listOf("t1"), fixture.cancelled)
    }

    @Test
    fun cancelLiveTurnReportsStopAccepted() {
        val fixture =
            Fixture().apply {
                phase = TurnState.RUNNING_TOOL
                cancelOutcome = TurnCancelOutcome.StoppedLive
            }

        assertEquals(CancelResult.StopAccepted, runBlocking { fixture.agent.cancel(turnId) })
    }

    @Test
    fun cancelNeedsReviewReportsReviewRequired() {
        val fixture =
            Fixture().apply {
                phase = TurnState.NEEDS_REVIEW
                cancelOutcome = TurnCancelOutcome.ReviewRequired
            }

        assertEquals(CancelResult.ReviewRequired, runBlocking { fixture.agent.cancel(turnId) })
    }

    @Test
    fun liveObservationCarriesStreamingTextAndTerminalPersistedText() {
        val fixture =
            Fixture().apply {
                observations =
                    listOf(
                        TurnObservation("t1", TurnState.RECEIVING_MODEL, streamingText = "hello"),
                        TurnObservation("t1", TurnState.COMPLETED),
                    )
                phase = TurnState.COMPLETED
                assistantText = "all done"
            }

        val frames = runBlocking { fixture.agent.observe(turnId).toList() }

        assertEquals(listOf(TurnState.RECEIVING_MODEL, TurnState.COMPLETED), frames.map { it.phase })
        assertEquals("hello", frames.first().assistantText)
        assertEquals("all done", frames.last().assistantText)
    }

    @Test
    fun lateSubscriberFallsBackToPersistedTerminal() {
        val fixture =
            Fixture().apply {
                phase = TurnState.COMPLETED
                assistantText = "final"
            }

        val frames = runBlocking { fixture.agent.observe(turnId).toList() }

        assertEquals(1, frames.size)
        assertEquals(TurnState.COMPLETED, frames.single().phase)
        assertEquals("final", frames.single().assistantText)
    }

    @Test
    fun parkedObservationEndsWithoutDuplicatePersistedFrame() {
        val fixture =
            Fixture().apply {
                observations =
                    listOf(
                        TurnObservation(
                            "t1",
                            TurnState.NEEDS_REVIEW,
                            errorLabel = "review",
                        ),
                    )
                phase = TurnState.NEEDS_REVIEW
            }

        val frames = runBlocking { fixture.agent.observe(turnId).toList() }

        assertEquals(listOf(TurnState.NEEDS_REVIEW), frames.map { it.phase })
        assertEquals("review", frames.single().errorLabel)
    }

    @Test
    fun liveStreamEndingEarlyFallsBackToDurablePhase() {
        val fixture =
            Fixture().apply {
                observations = listOf(TurnObservation("t1", TurnState.RECEIVING_MODEL, streamingText = "partial"))
                phase = TurnState.COMPLETED
                assistantText = "final"
            }

        val frames = runBlocking { fixture.agent.observe(turnId).toList() }

        assertEquals(listOf(TurnState.RECEIVING_MODEL, TurnState.COMPLETED), frames.map { it.phase })
        assertEquals("final", frames.last().assistantText)
    }

    private class Fixture : TurnRuntimeView {
        var nextStartTurnId: String? = "t1"
        var lastCommand: SubmitTurnCommand? = null
        var lastControl: RunControlConfig? = null
        var phase: TurnState? = null
        var assistantText: String? = null
        var observations: List<TurnObservation> = emptyList()
        var cancelOutcome: TurnCancelOutcome? = null
        val cancelled = mutableListOf<String>()
        val startedTurns = mutableListOf<String>()
        private val claimedClientIds = HashMap<String, String>()

        val agent =
            AppAgentRuntime(
                startTurn = { command, control ->
                    claimedClientIds[command.clientRequestId]
                        ?: nextStartTurnId?.also { turn ->
                            lastCommand = command
                            lastControl = control
                            claimedClientIds[command.clientRequestId] = turn
                            startedTurns += turn
                        }
                },
                cancelTurn = { id ->
                    cancelled += id
                    cancelOutcome ?: TurnCancelOutcome.AlreadyTerminal(requireNotNull(phase))
                },
                runtime = this,
            )

        override fun observe(turnId: String): Flow<TurnObservation> = flowOf(*observations.toTypedArray())

        override fun persistedPhase(turnId: String): TurnState? = phase

        override fun persistedAssistantText(turnId: String): String? = assistantText
    }
}
