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
        clientRequestId: String = "req-1",
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
        clientRequestId = clientRequestId,
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

    @Test
    fun aResubmittedClientRequestIdReturnsTheSameTurnWithoutStartingASecond() {
        // HX2-01 §2e: the adapter forwards the stable client-request id; the host dedups on it,
        // so a re-driven submission with the same id returns the already-started turn — the host
        // never starts a second turn (this is what collapses a re-driven egress confirm).
        val fake = host()
        val runtime = AppAgentRuntime(fake)
        runBlocking {
            val first = runtime.submit(command(clientRequestId = "req-same"))
            val resubmitted = runtime.submit(command(clientRequestId = "req-same"))
            assertEquals(first, resubmitted)
            assertEquals(1, fake.startedTurns.size)
        }
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

    @Test
    fun cancelOfAParkedInterruptedTurnIsDiscardedAndReportsCancelled() {
        // A parked (INTERRUPTED) turn has no live loop; the host discards it to CANCELLED, so the
        // result is still Cancelled (honest — a real cancel happened, not a silent no-op).
        val fake = host().apply { phase = TurnState.INTERRUPTED }
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
                frames = emptyList() // not live: the turn already ended before we subscribed
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
                frames = emptyList()
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

    @Test
    fun aBackgroundTurnReachingItsCleanTerminalIsObservedToEnd() {
        // HX2-01 §2c: the point of persistent observation — a turn that is NOT the open session's
        // active turn (a background turn) still streams to its terminal via the per-turn
        // live-frame channel. A clean COMPLETED carries no status label, so the host emits its
        // terminal directly to that channel; the observer must receive it and the stream must END
        // (toList returns) rather than hang waiting for a frame that never comes.
        val fake =
            host().apply {
                frames =
                    listOf(
                        TurnUi("t1", TurnState.RECEIVING_MODEL, "thinking…", null, false),
                        TurnUi("t1", TurnState.COMPLETED, null, null, false), // the null-label terminal
                    )
                assistantText = "done in the background"
            }
        val frames = runBlocking { AppAgentRuntime(fake).observe(turnId).toList() }

        assertEquals(listOf(TurnState.RECEIVING_MODEL, TurnState.COMPLETED), frames.map { it.phase })
        assertEquals("done in the background", frames[1].assistantText)
        assertTrue(frames[1].isTerminal)
    }

    @Test
    fun whenTheLiveStreamEndsWithoutATerminalTheObserverFallsBackToThePersistedPhase() {
        // HX2-01 §2c robustness: if the live-frame stream ends without a terminal snapshot (a
        // back-pressured consumer dropped the terminal frame), the observer is not stranded — it
        // projects the turn's persisted phase, so a COMPLETED turn is still reported completed.
        val fake =
            host().apply {
                frames = listOf(TurnUi("t1", TurnState.RECEIVING_MODEL, "partial", null, false))
                phase = TurnState.COMPLETED
                assistantText = "final"
            }
        val frames = runBlocking { AppAgentRuntime(fake).observe(turnId).toList() }

        assertEquals(listOf(TurnState.RECEIVING_MODEL, TurnState.COMPLETED), frames.map { it.phase })
        assertEquals("final", frames[1].assistantText)
        assertTrue(frames[1].isTerminal)
    }

    private class FakeAgentTurnHost : AgentTurnHost {
        var nextStartTurnId: String? = "t1"
        var lastStartSession: String? = null
        var lastStart: RunControlConfig? = null
        var lastStartAttachments: List<AttachmentBindingIntent> = emptyList()
        var phase: TurnState? = null
        var assistantText: String? = null
        var frames: List<TurnUi> = emptyList()
        val cancelled = mutableListOf<String>()
        val startedTurns = mutableListOf<String>() // turn ids actually started (a dedup hit adds none)
        private val claimedClientIds = HashMap<String, String>()

        override suspend fun startTurn(
            sessionId: String,
            clientRequestId: String,
            text: String?,
            providerId: String,
            retryTurnId: String?,
            goalId: String?,
            attachments: List<AttachmentBindingIntent>,
            control: RunControlConfig,
        ): String? {
            // Mirror the production host (HX2-01 §2e): idempotent by clientRequestId — a re-driven
            // start carrying an already-claimed id returns the existing turn, never a second.
            claimedClientIds[clientRequestId]?.let { existing -> return existing }
            lastStartSession = sessionId
            lastStart = control
            lastStartAttachments = attachments
            if (nextStartTurnId != null) {
                claimedClientIds[clientRequestId] = nextStartTurnId!!
                startedTurns += nextStartTurnId!!
            }
            return nextStartTurnId
        }

        override suspend fun cancelTurn(turnId: String): TurnCancelOutcome {
            cancelled += turnId
            // Mirror the host: a parked (INTERRUPTED) turn is discarded, a live one stopped.
            return when (phase) {
                TurnState.INTERRUPTED -> TurnCancelOutcome.DiscardedParked
                else -> TurnCancelOutcome.StoppedLive
            }
        }

        override fun observeTurnFrames(turnId: String): Flow<TurnUi> = flowOf(*frames.toTypedArray())

        override fun persistedPhase(turnId: String): TurnState? = phase

        override fun persistedAssistantText(turnId: String): String? = assistantText
    }
}
