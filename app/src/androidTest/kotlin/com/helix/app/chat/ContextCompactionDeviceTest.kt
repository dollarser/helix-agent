package com.helix.app.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.app.provider.ProviderContextSettings
import com.helix.app.runcontrol.RunControlConfig
import com.helix.core.model.Clock
import com.helix.core.model.ModelEvent
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

class ContextCompactionDeviceTest {
    private val clock =
        object : Clock {
            override fun now(): Instant = Instant.ofEpochMilli(1000)
        }
    private val control =
        RunControlConfig(
            com.helix.core.model.AgentMode.CHAT,
            false,
            com.helix.app.runcontrol.TurnBudgetBounds.DEFAULT,
        )
    private var sequence = 0

    private fun next() = "context-${sequence++}"

    @Test fun completedSummaryPreservesHistoryAndSurvivesReopen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "context-${UUID.randomUUID()}.db"
        val files = File(context.filesDir, name)
        var storage = HelixStorage.open(context, name, files)
        try {
            val coordinator = seed(storage)
            val before = storage.messages.listBySession("s")
            val original = before.associate { it.id to storage.messages.readContent(it) }
            val request = request(storage)
            val plan =
                requireNotNull(
                    ContextCompaction.plan(
                        storage,
                        "s",
                        request,
                        control,
                        ProviderContextSettings(),
                        true,
                        coordinator.id,
                    ),
                )
            val stream = coordinator.beginModelStream(compacting = true)
            stream.apply(ModelEvent.TextDelta("Keep the original constraints and unresolved work."))
            stream.apply(ModelEvent.Completed("stop"))
            coordinator.commitCompaction(plan, null)
            coordinator.terminalize(ModelStreamTerminal(TurnState.COMPLETED, null))
            val checkpoint =
                requireNotNull(
                    ContextCompaction.checkpoint(storage, storage.messages.listBySession("s")),
                )
            assertTrue(checkpoint.coveredThrough < before.last().sequence)
            assertTrue(checkpoint.estimatedInputTokens!! < request.inputTokens())
            original.forEach { (id, text) ->
                assertEquals(text, storage.messages.readContent(storage.messages.resolve(id)))
            }
            assertEquals(before.size + 1, storage.messages.listBySession("s").size)
            val kept = ContextCompaction.retained(storage.messages.listBySession("s"), checkpoint)
            assertTrue(kept.any { it.turnId == coordinator.id })
            assertTrue(kept.any { it.turnId == "old-2" })
            assertFalse(kept.any { it.turnId == "old-0" })
            val retained = plan.retainedRequest.messages
            assertEquals(listOf("tool-2"), retained.flatMap { it.toolCalls }.map { it.id.value })
            assertEquals(listOf("tool-2"), retained.mapNotNull { it.toolCallId?.value })
            storage.close()
            storage = HelixStorage.open(context, name, files)
            assertEquals(checkpoint, ContextCompaction.checkpoint(storage, storage.messages.listBySession("s")))
        } finally {
            storage.close()
            context.deleteDatabase(name)
            files.deleteRecursively()
        }
    }

    @Test fun partialOrFailedSummaryNeverBecomesAResponseOrCheckpoint() {
        withStorage { storage ->
            val coordinator = seed(storage)
            val plan =
                requireNotNull(
                    ContextCompaction.plan(
                        storage,
                        "s",
                        request(storage),
                        control,
                        ProviderContextSettings(),
                        true,
                        coordinator.id,
                    ),
                )
            val before = storage.messages.listBySession("s").size
            coordinator.beginModelStream(compacting = true).apply(ModelEvent.TextDelta("unfinished summary"))
            assertThrows(IllegalArgumentException::class.java) { coordinator.commitCompaction(plan, null) }
            coordinator.terminalize(ModelStreamTerminal(TurnState.CANCELLED, null))
            assertNull(ContextCompaction.checkpoint(storage, storage.messages.listBySession("s")))
            assertEquals(before, storage.messages.listBySession("s").size)
            assertEquals(
                "CANCELLED",
                storage.modelCalls
                    .listByTurn(coordinator.id)
                    .single()
                    .state,
            )
        }
    }

    @Test fun automaticThresholdAndDisableDoNotAffectManualCompaction() {
        withStorage { storage ->
            val coordinator = seed(storage)
            val request = request(storage)
            assertNull(
                ContextCompaction.plan(
                    storage,
                    "s",
                    request,
                    control,
                    ProviderContextSettings(),
                    false,
                    coordinator.id,
                ),
            )
            val low = ProviderContextSettings(manualWindow = 8192, triggerPercent = 10)
            assertNotNull(ContextCompaction.plan(storage, "s", request, control, low, false, coordinator.id))
            assertNull(
                ContextCompaction.plan(
                    storage,
                    "s",
                    request,
                    control,
                    low.copy(autoCompact = false),
                    false,
                    coordinator.id,
                ),
            )
            assertNotNull(
                ContextCompaction.plan(
                    storage,
                    "s",
                    request,
                    control,
                    low.copy(autoCompact = false),
                    true,
                    coordinator.id,
                ),
            )
        }
    }

    @Test fun checkpointAdvanceCreatesASecondCallWithoutAToolBatch() {
        withStorage { storage ->
            val coordinator = seed(storage)
            val plan =
                requireNotNull(
                    ContextCompaction.plan(
                        storage,
                        "s",
                        request(storage),
                        control,
                        ProviderContextSettings(),
                        true,
                        coordinator.id,
                    ),
                )
            val stream = coordinator.beginModelStream(compacting = true)
            stream.apply(ModelEvent.TextDelta("Concise earlier history."))
            stream.apply(ModelEvent.Completed("stop"))
            coordinator.commitCompaction(plan, "next-call")
            assertEquals(2, storage.modelCalls.listByTurn(coordinator.id).size)
            assertEquals(TurnState.WAITING_MODEL.name, storage.turns.resolve(coordinator.id).state)
            val reply = coordinator.beginModelStream()
            reply.apply(ModelEvent.TextDelta("The requested answer."))
            reply.apply(ModelEvent.Completed("stop"))
            coordinator.terminalize(ModelStreamTerminal(TurnState.COMPLETED, null))
            assertEquals("COMPLETED", storage.modelCalls.resolve("next-call").state)
            assertTrue(
                storage.messages.listBySession("s").any {
                    storage.messages.readContent(it) == "The requested answer."
                },
            )
        }
    }

    @Test fun recoveryKeepsCommittedSummaryAndInterruptsOnlyThePendingCall() {
        withStorage { storage ->
            val coordinator = seed(storage)
            val plan =
                requireNotNull(
                    ContextCompaction.plan(
                        storage,
                        "s",
                        request(storage),
                        control,
                        ProviderContextSettings(),
                        true,
                        coordinator.id,
                    ),
                )
            val stream = coordinator.beginModelStream(compacting = true)
            stream.apply(ModelEvent.TextDelta("Durable constraints."))
            stream.apply(ModelEvent.Completed("stop"))
            coordinator.commitCompaction(plan, "pending-after-summary")
            val checkpoint = ContextCompaction.checkpoint(storage, storage.messages.listBySession("s"))
            val recovery =
                com.helix.app.recovery
                    .RecoveryCoordinatorApp(storage, clock)
            recovery.recover()
            assertEquals("INTERRUPTED", storage.turns.resolve(coordinator.id).state)
            assertEquals("INTERRUPTED", storage.modelCalls.resolve("pending-after-summary").state)
            assertEquals(1, storage.modelCalls.listByTurn(coordinator.id).count { it.state == "COMPLETED" })
            assertEquals(checkpoint, ContextCompaction.checkpoint(storage, storage.messages.listBySession("s")))
            recovery.recover()
            assertEquals(2, storage.modelCalls.listByTurn(coordinator.id).size)
        }
    }

    @Test fun refusalNeverPublishesACheckpoint() {
        withStorage { storage ->
            val coordinator = seed(storage)
            val plan =
                requireNotNull(
                    ContextCompaction.plan(
                        storage,
                        "s",
                        request(storage),
                        control,
                        ProviderContextSettings(),
                        true,
                        coordinator.id,
                    ),
                )
            val stream = coordinator.beginModelStream(compacting = true)
            stream.apply(ModelEvent.TextDelta("Incomplete notes."))
            stream.apply(ModelEvent.Refusal("Refused"))
            stream.apply(ModelEvent.Completed("stop"))
            assertThrows(IllegalArgumentException::class.java) { coordinator.commitCompaction(plan, null) }
            coordinator.terminalize(stream.terminal(false))
            assertNull(ContextCompaction.checkpoint(storage, storage.messages.listBySession("s")))
            assertEquals("REFUSAL", storage.turns.resolve(coordinator.id).errorCode)
        }
    }

    private fun seed(storage: HelixStorage): TurnCoordinator {
        storage.sessions.create("s", "Compaction fixture", null, null, 1000)
        repeat(3) { index ->
            val old =
                TurnCoordinator.start(
                    storage,
                    clock,
                    ::next,
                    TurnStartSpec("s", "old-$index", next(), "{}", "constraint-$index " + "context ".repeat(600)),
                )
            seedToolPair(storage, "old-$index", index)
            val stream = old.beginModelStream()
            stream.apply(ModelEvent.TextDelta("answer-$index"))
            stream.apply(ModelEvent.Completed("stop"))
            old.terminalize(ModelStreamTerminal(TurnState.COMPLETED, null))
        }
        return TurnCoordinator.start(
            storage,
            clock,
            ::next,
            TurnStartSpec("s", "current", next(), "{}", "Continue the work"),
        )
    }

    private fun seedToolPair(
        storage: HelixStorage,
        turnId: String,
        index: Int,
    ) {
        storage.messages.append(
            next(),
            "s",
            turnId,
            "ASSISTANT",
            ChatHistoryBuilder.KIND_TOOL_CALLS,
            """[{"id":"tool-$index","name":"time.now","arguments":"{}"}]""",
        )
        storage.messages.append(
            next(),
            "s",
            turnId,
            "TOOL",
            ChatHistoryBuilder.KIND_TOOL_RESULT,
            """{"id":"tool-$index","tool":"time.now","status":"COMPLETED","summary":"Verified time"}""",
        )
    }

    private fun request(storage: HelixStorage) =
        ChatContextRequest(
            "fixture",
            ChatHistoryBuilder.toModelMessagesStrict(
                storage.messages.listBySession("s").map {
                    ChatHistoryBuilder.PersistedRow(
                        turnId = it.turnId,
                        role = it.role,
                        kind = it.kind,
                        content = storage.messages.readContent(it),
                        messageId = it.id,
                    )
                },
            ),
            emptyList(),
            512,
            ReasoningEffort.OFF,
        )

    private fun withStorage(block: (HelixStorage) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "context-${UUID.randomUUID()}.db"
        val files = File(context.filesDir, name)
        val storage = HelixStorage.open(context, name, files)
        try {
            block(storage)
        } finally {
            storage.close()
            context.deleteDatabase(name)
            files.deleteRecursively()
        }
    }
}
