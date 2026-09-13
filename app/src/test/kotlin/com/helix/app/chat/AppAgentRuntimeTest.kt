package com.helix.app.chat

import com.helix.app.runcontrol.RunControlConfig
import com.helix.core.agent.AttachmentBindingIntent
import com.helix.core.agent.CancelResult
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

/**
 * Unit tests for [AppAgentRuntime] (research doc section 34; HX2-01): the translation between the
 * framework-free [com.helix.core.agent.AgentRuntime] contract and the production turn path,
 * exercised through a hand-written [AgentTurnHost] fake — no service, no coroutine harness.
 */
class AppAgentRuntimeTest {
    private val sessionId = SessionId("s1")
    private val providerId = ProviderId("p1")
    private val turnId = TurnId("t1")
    private val goalId = GoalId("g1")
    private val budgets = TurnBudgets(8, 9, 128_000, 4_096, 160_000)

    private fun host() = FakeAgentTurnHost()

    private fun command(
        mode: AgentMode = AgentMode.CHAT,
        text: String? = "hi",
        reasoning: ReasoningEffort = ReasoningEffort.OFF,
        goalId: GoalId? = null,
        retryTurnId: TurnId? = null,
        attachments: List<AttachmentBindingIntent> = emptyList(),
    ) = SubmitTurnCommand(
        sessionId,
        providerId,
        mode,
        text,
        budgets,
        reasoning = reasoning,
        goalId = goalId,
        retryTurnId = retryTurnId,
        attachments = attachments,
    )

    // --- submit: the command becomes a per-turn run control and a turn start ---

    @Test
    fun submitReturnsTheStartedTurnIdAndMapsTheControl() {
        val fake = host()
        val runtime = AppAgentRuntime(fake)

        val id =
            runBlocking {
                runtime.submit(
                    command(mode = AgentMode.GOAL, text = null, reasoning = ReasoningEffort.MEDIUM, goalId = goalId),
                )
            }

        assertEquals(turnId, id)
        assertEquals("s1", fake.lastStartSession)
        val control = fake.lastStart!!
        assertEquals(AgentMode.GOAL, control.mode)
        assertEquals(budgets, control.budgets)
        assertEquals(ReasoningEffort.MEDIUM, control.reasoning)
    }

    @Test
    fun submitSurfacesABlockedStart() {
        val fake = host().apply { nextStartTurnId = null }
        val runtime = AppAgentRuntime(fake)

        assertThrows(TurnStartBlocked::class.java) {
            runBlocking { runtime.submit(command()) }
        }
    }

    @Test
    fun submitCarriesTheProducersApprovedAttachmentIntents() {
        val intent = AttachmentBindingIntent("art-1", "sha-1")
        val fake = host()
        val runtime = AppAgentRuntime(fake)

        runBlocking { runtime.submit(command(attachments = listOf(intent))) }

        assertEquals(listOf(intent), fake.lastStartAttachments)
    }

    // --- cancel ---

    @Test
    fun cancelOfAnUnknownTurnIsNotFound() {
        val runtime = AppAgentRuntime(host())
        assertTrue(runBlocking { runtime.cancel(turnId) } is CancelResult.NotFound)
    }

    @Test
    fun cancelOfATerminalTurnIsAlreadyTerminal() {
        val runtime = AppAgentRuntime(host().apply { phase = TurnState.CANCELLED })
        val result = runBlocking { runtime.cancel(turnId) }
        assertTrue(result is CancelResult.AlreadyTerminal && result.phase == TurnState.CANCELLED)
    }

    @Test
    fun cancelOfALiveTurnCancelsAndReportsCancelled() {
        val fake = host().apply { phase = TurnState.RUNNING_TOOL }
        val runtime = AppAgentRuntime(fake)
        val result = runBlocking { runtime.cancel(turnId) }
        assertTrue(result is CancelResult.Cancelled)
        assertEquals(listOf("t1"), fake.cancelled)
    }

    // --- observe ---

    @Test
    fun observeStreamsLiveFramesThroughTheTerminalFrame() {
        val fake =
            host().apply {
                frames =
                    listOf(
                        TurnUi("t1", TurnState.WAITING_MODEL, null, null, false),
                        TurnUi("t1", TurnState.RUNNING_TOOL, "hello", null, false),
                        TurnUi("t1", TurnState.COMPLETED, null, null, false),
                    )
                assistantText = "all done"
            }
        val frames = runBlocking { AppAgentRuntime(fake).observe(turnId).toList() }

        assertEquals(
            listOf(TurnState.WAITING_MODEL, TurnState.RUNNING_TOOL, TurnState.COMPLETED),
            frames.map { it.phase },
        )
        assertEquals("hello", frames[1].assistantText) // live streaming text on a non-terminal frame
        assertEquals("all done", frames[2].assistantText) // the terminal frame carries the persisted text
        assertTrue(frames[2].isTerminal)
    }

    @Test
    fun aLateSubscriberSeesThePersistedTerminalState() {
        val fake =
            host().apply {
                frames = listOf(null) // no live frame: the turn already left the open session
                phase = TurnState.COMPLETED
                assistantText = "final"
            }
        val frames = runBlocking { AppAgentRuntime(fake).observe(turnId).toList() }

        assertEquals(1, frames.size)
        assertEquals(TurnState.COMPLETED, frames[0].phase)
        assertEquals("final", frames[0].assistantText)
        assertTrue(frames[0].isTerminal)
    }

    @Test
    fun observeOfAnUnknownTurnEmitsNothing() {
        val fake =
            host().apply {
                frames = listOf(null)
                phase = null
            }
        val frames = runBlocking { AppAgentRuntime(fake).observe(turnId).toList() }
        assertTrue(frames.isEmpty())
    }

    @Test
    fun aLiveFailedTerminalFrameKeepsItsErrorLabelAndRetryable() {
        val fake =
            host().apply {
                frames = listOf(TurnUi("t1", TurnState.FAILED, null, "model error", true))
                assistantText = "partial"
            }
        val frames = runBlocking { AppAgentRuntime(fake).observe(turnId).toList() }

        assertEquals(1, frames.size)
        assertEquals("model error", frames[0].errorLabel)
        assertTrue(frames[0].retryable)
        assertEquals("partial", frames[0].assistantText)
    }

    private class FakeAgentTurnHost : AgentTurnHost {
        var nextStartTurnId: String? = "t1"
        var lastStartSession: String? = null
        var lastStart: RunControlConfig? = null
        var lastStartAttachments: List<AttachmentBindingIntent> = emptyList()
        var phase: TurnState? = null
        var assistantText: String? = null
        var frames: List<TurnUi?> = emptyList()
        val cancelled = mutableListOf<String>()

        override suspend fun startTurn(
            sessionId: String,
            text: String?,
            providerId: String,
            retryTurnId: String?,
            goalId: String?,
            attachments: List<AttachmentBindingIntent>,
            control: RunControlConfig,
        ): String? {
            lastStartSession = sessionId
            lastStart = control
            lastStartAttachments = attachments
            return nextStartTurnId
        }

        override fun cancelTurn(turnId: String) {
            cancelled += turnId
        }

        override val activeTurn: Flow<TurnUi?>
            get() = flowOf(*frames.toTypedArray())

        override fun persistedPhase(turnId: String): TurnState? = phase

        override fun persistedAssistantText(turnId: String): String? = assistantText
    }
}
