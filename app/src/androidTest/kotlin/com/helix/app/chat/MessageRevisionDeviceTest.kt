package com.helix.app.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.app.agent.ContextCompaction
import com.helix.app.agent.ContextHistory
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

class MessageRevisionDeviceTest {
    @Test fun revisionExcludesOldAnswerToolsAndSummaryButRetainsAuditAndLateResults() =
        fixture { storage ->
            storage.messages.append("prefix", "s", null, "USER", "TEXT", "keep prefix")
            val old = start(storage, "old", "wrong input")
            storage.messages.append("answer", "s", "old", "ASSISTANT", "TEXT", "wrong answer")
            storage.messages.append("tool", "s", "old", "TOOL", "TOOL_RESULT", "old tool result")
            storage.messages.append(
                "summary",
                "s",
                "old",
                "ASSISTANT",
                ContextCompaction.KIND,
                """{"coveredThrough":3,"summary":"wrong summary"}""",
            )
            old.settleFixtureTerminal(ModelStreamTerminal(TurnState.COMPLETED, null))
            val target = storage.messages.latestUser("s")!!.id
            start(
                storage,
                "edit",
                "correct input",
                target,
            ).settleFixtureTerminal(ModelStreamTerminal(TurnState.COMPLETED, null))
            storage.messages.append("late", "s", "old", "TOOL", "TOOL_RESULT", "late old result")
            val history = ContextHistory.load(storage, "s")
            assertNull(history.checkpoint)
            assertEquals(listOf("keep prefix", "correct input"), history.rows.map { storage.messages.readContent(it) })
            assertEquals("edit", storage.messages.resolve("late").supersededBy)
            assertEquals("wrong answer", storage.messages.readContent(storage.messages.resolve("answer")))
            assertEquals(2, storage.turns.listBySession("s").size)
            assertEquals(1, storage.sessions.list().size)
        }

    @Test fun staleEarlierTargetAndRunningTurnLeaveAllHistoryUnchanged() =
        fixture { storage ->
            val running = start(storage, "old", "original")
            val target = storage.messages.latestUser("s")!!.id
            // A busy session is refused by admission (Blocked result), not by a thrown exception.
            assertTrue(admit(storage, "blocked", "edit", target) is TurnAdmissionResult.Blocked)
            assertEquals(listOf("old"), storage.turns.listBySession("s").map { it.id })
            running.settleFixtureTerminal(ModelStreamTerminal(TurnState.CANCELLED, null))
            start(storage, "next", "new latest").settleFixtureTerminal(ModelStreamTerminal(TurnState.COMPLETED, null))
            // A stale (no longer latest) revision target still fails closed inside `reviseLatest`.
            assertThrows(IllegalArgumentException::class.java) { admit(storage, "stale", "edit", target) }
            assertEquals(
                listOf("original", "new latest"),
                storage.messages.listBySession("s").map { storage.messages.readContent(it) },
            )
            assertTrue(storage.messages.allRevisions("s").all { it.supersededBy == null })
        }

    @Test fun failedAtomicTurnCreationRollsBackSupersedingAndRepeatedRevisionsKeepPrefix() =
        fixture { storage ->
            start(storage, "old", "original").settleFixtureTerminal(ModelStreamTerminal(TurnState.COMPLETED, null))
            val target = storage.messages.latestUser("s")!!.id
            assertThrows(Exception::class.java) {
                TurnCoordinator.start(
                    storage,
                    testClock,
                    { "new-message" },
                    TurnStartSpec(
                        "s",
                        "broken",
                        "model-old",
                        "snapshot",
                        "edit",
                        clientRequestId = "broken",
                        revisedMessageId = target,
                    ),
                )
            }
            assertEquals("original", storage.messages.readContent(storage.messages.latestUser("s")!!))
            assertEquals(1, storage.turns.listBySession("s").size)
            val first = start(storage, "edit1", "first revision", target)
            first.settleFixtureTerminal(ModelStreamTerminal(TurnState.COMPLETED, null))
            val last = start(storage, "edit2", "second revision", storage.messages.latestUser("s")!!.id)
            last.settleFixtureTerminal(ModelStreamTerminal(TurnState.COMPLETED, null))
            val bodies = ContextHistory.load(storage, "s").rows.map { storage.messages.readContent(it) }
            assertEquals(listOf("second revision"), bodies)
            assertEquals(3, storage.messages.allRevisions("s").size)
        }

    /**
     * REVISE is an admission concern (HXA-220 moved history mutation out of `TurnCoordinator.start`,
     * which now only creates the Turn row). The fixture must therefore go through `TurnAdmission`
     * for a revision to take effect at all.
     */
    private fun start(
        storage: HelixStorage,
        id: String,
        text: String,
        target: String? = null,
    ): TurnCoordinator =
        (
            admit(storage, id, text, target) as TurnAdmissionResult.Started
        ).turn.coordinator.also { it.beginModelStream() }

    private fun admit(
        storage: HelixStorage,
        id: String,
        text: String,
        target: String? = null,
    ): TurnAdmissionResult =
        TurnAdmission(storage, testClock) { UUID.randomUUID().toString() }
            .start(
                TurnStartSpec(
                    sessionId = "s",
                    turnId = id,
                    firstModelCallId = "model-$id",
                    providerSnapshot = "snapshot",
                    userText = text,
                    clientRequestId = id,
                    revisedMessageId = target,
                ),
                control = control,
                wakeReason = GoalWakeReason.USER_OPEN,
                providerId = "provider",
                modelId = "model",
            )

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
        val name = "revision-${UUID.randomUUID()}.db"
        val root = File(context.filesDir, name)
        val storage = HelixStorage.open(context, name, root)
        try {
            storage.sessions.create("s", "Revision", null, null, 1)
            block(storage)
        } finally {
            storage.close()
            context.deleteDatabase(name)
            root.deleteRecursively()
        }
    }
}
