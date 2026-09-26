package com.helix.app.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.app.agent.ModelStreamTerminal
import com.helix.app.agent.TurnCoordinator
import com.helix.app.agent.TurnStartSpec
import com.helix.app.engine.TurnAdmission
import com.helix.app.engine.TurnAdmissionResult
import com.helix.app.runcontrol.RunControlConfig
import com.helix.core.agent.GoalWakeReason
import com.helix.core.model.AgentMode
import com.helix.core.model.Clock
import com.helix.core.model.GoalBudgets
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

class MessageRegenerateDeviceTest {
    @Test
    fun regenerateSupersedesOldAssistantAndSubsequentTurnArtifacts() =
        fixture { storage ->
            storage.messages.append("u1", "s", "t1", "USER", "TEXT", "Calculate 2+2")
            val turn1 = start(storage, "t1", null)
            val asst1 = storage.messages.append("a1", "s", "t1", "ASSISTANT", "TEXT", "It is 5")
            storage.messages.append("tool-1", "s", "t1", "TOOL", "TOOL_RESULT", "tool result")
            turn1.settleFixtureTerminal(ModelStreamTerminal(TurnState.COMPLETED, null))

            val replacement = start(storage, "t2", null, regenerateTarget = asst1.id)
            replacement.settleFixtureTerminal(ModelStreamTerminal(TurnState.COMPLETED, null))

            val reloadedUser = storage.messages.resolve("u1")
            val reloadedAsst = storage.messages.resolve("a1")
            val reloadedTool = storage.messages.resolve("tool-1")

            assertNull("user message must remain active", reloadedUser.supersededBy)
            assertEquals("req-t2", reloadedAsst.supersededBy)
            assertEquals("req-t2", reloadedTool.supersededBy)
            assertEquals(2, storage.turns.listBySession("s").size)
            assertNotNull(storage.turnRuntimeRecords.find("t2"))
        }

    @Test
    fun failedAtomicRegenerateRollsBackSupersedingAndPreservesOldAnswer() =
        fixture { storage ->
            storage.messages.append("u1", "s", "t1", "USER", "TEXT", "Hello")
            val turn1 = start(storage, "t1", null)
            val asst1 = storage.messages.append("a1", "s", "t1", "ASSISTANT", "TEXT", "Original answer")
            // Intentionally leave turn1 running (non-terminal) so session is busy!
            // Attempting to regenerate while session is busy must fail atomically
            val refused =
                TurnAdmission(storage, testClock) { UUID.randomUUID().toString() }
                    .start(
                        TurnStartSpec(
                            sessionId = "s",
                            turnId = "t-fail",
                            firstModelCallId = "m-fail",
                            providerSnapshot = "snapshot",
                            userText = null,
                            clientRequestId = "req-fail",
                            inputFingerprint = "fp-fail",
                            regenerateMessageId = asst1.id,
                        ),
                        control = control,
                        wakeReason = GoalWakeReason.USER_OPEN,
                        providerId = "provider",
                        modelId = "model",
                    )
            assertTrue(refused is TurnAdmissionResult.Blocked)

            val preservedAsst = storage.messages.resolve(asst1.id)
            assertNull("old answer must NOT be superseded after admission failure", preservedAsst.supersededBy)
            assertEquals("Original answer", storage.messages.readContent(preservedAsst))
            assertEquals(1, storage.turns.listBySession("s").size)
        }

    @Test
    fun staleOrAlreadySupersededTargetFailsClosedWithoutModifyingStorage() =
        fixture { storage ->
            storage.messages.append("u1", "s", "t1", "USER", "TEXT", "Question 1")
            val turn1 = start(storage, "t1", null)
            val asst1 = storage.messages.append("a1", "s", "t1", "ASSISTANT", "TEXT", "Answer 1")
            turn1.settleFixtureTerminal(ModelStreamTerminal(TurnState.COMPLETED, null))

            // Regenerate a1 once
            val turn2 = start(storage, "t2", null, regenerateTarget = asst1.id)
            val asst2 = storage.messages.append("a2", "s", "t2", "ASSISTANT", "TEXT", "Answer 2")
            turn2.settleFixtureTerminal(ModelStreamTerminal(TurnState.COMPLETED, null))

            // Trying to regenerate a1 again fails because it is already superseded
            assertThrows(Exception::class.java) {
                start(storage, "t3", null, regenerateTarget = asst1.id)
            }

            // a2 is the latest active assistant message and is not affected
            val currentAsst = storage.messages.resolve(asst2.id)
            assertNull(currentAsst.supersededBy)
            assertEquals(2, storage.turns.listBySession("s").size)
        }

    @Test
    fun duplicateRegenerateReceiptReturnsOriginalReplacementBeforeAnyFreshSideEffect() =
        fixture { storage ->
            val answerId = completedOriginalAnswer(storage)
            val admission = TurnAdmission(storage, testClock) { UUID.randomUUID().toString() }
            val first =
                admitRegenerate(admission, "t2", "regen-request", "regen-fingerprint", answerId)
                    as TurnAdmissionResult.Started
            first.turn.coordinator.beginModelStream()
            first.turn.coordinator.settleFixtureTerminal(ModelStreamTerminal(TurnState.COMPLETED, null))

            val probe = AdmissionProbe()
            val duplicate =
                admitRegenerate(admission, "t3", "regen-request", "regen-fingerprint", answerId, probe)

            assertEquals(TurnAdmissionResult.Deduplicated("t2"), duplicate)
            assertProbeUntouched(probe)
            assertNoProposedTurn(storage, "t3")
            assertEquals("regen-request", storage.messages.resolve(answerId).supersededBy)

            val conflict =
                admitRegenerate(admission, "t4", "regen-request", "different-fingerprint", answerId, probe)
            assertEquals(TurnAdmissionResult.Conflict, conflict)
            assertProbeUntouched(probe)
            assertNoProposedTurn(storage, "t4")
        }

    private fun completedOriginalAnswer(storage: HelixStorage): String {
        storage.messages.append("u1", "s", "t1", "USER", "TEXT", "Question")
        val original = start(storage, "t1", null)
        val answer = storage.messages.append("a1", "s", "t1", "ASSISTANT", "TEXT", "Original")
        original.settleFixtureTerminal(ModelStreamTerminal(TurnState.COMPLETED, null))
        return answer.id
    }

    private fun admitRegenerate(
        admission: TurnAdmission,
        turnId: String,
        requestId: String,
        fingerprint: String,
        answerId: String,
        probe: AdmissionProbe? = null,
    ): TurnAdmissionResult =
        admission.start(
            TurnStartSpec(
                sessionId = "s",
                turnId = turnId,
                firstModelCallId = "model-$turnId",
                providerSnapshot = "snapshot",
                userText = null,
                clientRequestId = requestId,
                inputFingerprint = fingerprint,
                regenerateMessageId = answerId,
            ),
            control = control,
            wakeReason = GoalWakeReason.USER_OPEN,
            providerId = "provider",
            modelId = "model",
            freshGuard = {
                probe?.let { it.freshGuardCalls += 1 }
                true
            },
            resolveGoal = {
                probe?.let {
                    it.goalResolverCalls += 1
                    error("receipt must return before Goal resolution")
                }
                null
            },
        )

    private fun assertProbeUntouched(probe: AdmissionProbe) {
        assertEquals(0, probe.freshGuardCalls)
        assertEquals(0, probe.goalResolverCalls)
    }

    private fun assertNoProposedTurn(
        storage: HelixStorage,
        turnId: String,
    ) {
        assertNull(storage.turns.find(turnId))
        assertNull(storage.turnRuntimeRecords.find(turnId))
        assertTrue(storage.modelCalls.listByTurn(turnId).isEmpty())
    }

    private data class AdmissionProbe(
        var freshGuardCalls: Int = 0,
        var goalResolverCalls: Int = 0,
    )

    private fun start(
        storage: HelixStorage,
        id: String,
        text: String?,
        regenerateTarget: String? = null,
    ): TurnCoordinator =
        (
            TurnAdmission(storage, testClock) { UUID.randomUUID().toString() }
                .start(
                    TurnStartSpec(
                        sessionId = "s",
                        turnId = id,
                        firstModelCallId = "model-$id",
                        providerSnapshot = "snapshot",
                        userText = text,
                        clientRequestId = "req-$id",
                        inputFingerprint = "fp-$id",
                        regenerateMessageId = regenerateTarget,
                    ),
                    control = control,
                    wakeReason = GoalWakeReason.USER_OPEN,
                    providerId = "provider",
                    modelId = "model",
                ) as TurnAdmissionResult.Started
        ).turn.coordinator.also { it.beginModelStream() }

    private val control =
        RunControlConfig(
            mode = AgentMode.CHAT,
            chatToolsEnabled = true,
            budgets = TurnBudgets(4, 4, 8_000, 2_000, 16_000),
            reasoning = ReasoningEffort.LOW,
            goalBudgets = GoalBudgets(8, 8, 16_000, 16_000, 32_000, 1),
        )

    private val testClock =
        object : Clock {
            override fun now(): Instant = Instant.ofEpochMilli(1000)
        }

    private fun fixture(block: (HelixStorage) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "regenerate-${UUID.randomUUID()}.db"
        val root = File(context.filesDir, name)
        val storage = HelixStorage.open(context, name, root)
        try {
            storage.sessions.create("s", "RegenerateSession", "provider", "model", 1)
            block(storage)
        } finally {
            storage.close()
            context.deleteDatabase(name)
            root.deleteRecursively()
        }
    }
}
