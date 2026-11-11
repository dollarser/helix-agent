package com.helix.app.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.app.agent.ChatContextProjection
import com.helix.app.agent.ChatHistoryBuilder
import com.helix.app.agent.ContextCompaction
import com.helix.app.agent.TurnStartSpec
import com.helix.app.test.completeResponseOrContinueFixture
import com.helix.app.test.settleFixtureTerminal
import com.helix.app.test.startFixtureTurn
import com.helix.core.agent.ModelStreamTerminal
import com.helix.core.agent.TurnContextRequest
import com.helix.core.agent.turn.TurnRoundCoordinator
import com.helix.core.model.Clock
import com.helix.core.model.ModelEvent
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import com.helix.provider.api.ProviderContextSettings
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
    private var sequence = 0

    private fun next() = "context-${sequence++}"

    @Test fun manualNoGainRetriesThenFailsWithoutCheckpointOrHistoryReplacement() {
        withStorage { storage ->
            val coordinator = seed(storage)
            val before = storage.messages.listBySession("s")
            val originals = before.associate { it.id to storage.messages.readContent(it) }
            val round =
                com.helix.app.agent.contextCompactionRound(
                    storage,
                    "s",
                    coordinator.id,
                    ProviderContextSettings(),
                    true,
                )
            var result: ModelStreamTerminal? = null
            kotlinx.coroutines.runBlocking {
                repeat(3) { attempt ->
                    val prepared = round.prepare(request(storage))
                    assertNull(prepared.failure)
                    val plan = requireNotNull(prepared.plan).copy(originalInputTokens = 32)
                    val stream = coordinator.beginModelStream(compacting = true)
                    stream.apply(ModelEvent.TextDelta("No useful reduction is possible."))
                    stream.apply(ModelEvent.Usage(872, 971))
                    stream.apply(ModelEvent.Completed("stop"))
                    result =
                        round.finish(
                            plan,
                            stream,
                            stream.terminal(false),
                            coordinator,
                            next(),
                            "Compacted",
                            "Unchanged",
                        )
                    if (attempt < 2) assertNull(result)
                }
                val blocked = round.prepare(request(storage))
                assertNull(blocked.request)
                assertEquals(result, blocked.failure)
            }
            assertEquals(ModelStreamTerminal(TurnState.FAILED, "CONTEXT_NO_GAIN"), result)
            coordinator.settleFixtureTerminal(requireNotNull(result))
            assertEquals("CONTEXT_NO_GAIN", storage.turns.resolve(coordinator.id).errorCode)
            assertNull(ContextCompaction.checkpoint(storage, storage.messages.listBySession("s")))
            originals.forEach { (id, text) ->
                assertEquals(text, storage.messages.readContent(storage.messages.resolve(id)))
            }
            assertEquals(before, storage.messages.listBySession("s"))
            val calls = storage.modelCalls.listByTurn(coordinator.id)
            assertEquals(3, calls.size)
            calls.forEach { call ->
                assertEquals("FAILED", call.state)
                assertTrue(call.usage!!.contains("\"inputTokens\":872"))
                assertTrue(call.usage!!.contains("\"outputTokens\":971"))
                assertTrue(ChatContextProjection.isSummary(storage, call.id))
            }
        }
    }

    @Test fun invalidSummaryFailureReasonPreservesPriorCheckpointAndOriginalRows() {
        withStorage { storage ->
            val coordinator = seed(storage)
            val plan =
                requireNotNull(
                    ContextCompaction.plan(
                        storage,
                        "s",
                        request(storage),
                        ProviderContextSettings(),
                        true,
                        coordinator.id,
                    ),
                )
            val valid = kotlinx.coroutines.runBlocking { coordinator.beginModelStream(compacting = true) }
            valid.apply(ModelEvent.TextDelta("Keep the original constraints and unresolved work."))
            valid.apply(ModelEvent.Completed("stop"))
            kotlinx.coroutines.runBlocking { coordinator.commitCompaction(plan, "retry-summary") }
            val before = storage.messages.listBySession("s")
            val original = before.associate { it.id to storage.messages.readContent(it) }
            val checkpoint = requireNotNull(ContextCompaction.checkpoint(storage, before))
            val invalid = kotlinx.coroutines.runBlocking { coordinator.beginModelStream(compacting = true) }
            invalid.apply(ModelEvent.TextDelta("<tool_call><function=helix>ui.snapshot</function></tool_call>"))
            invalid.apply(ModelEvent.Completed("stop"))
            kotlinx.coroutines.runBlocking {
                coordinator.commitCompaction(plan, "resume-original", failureReason = "CONTEXT_SUMMARY_INVALID")
            }
            assertEquals(before, storage.messages.listBySession("s"))
            original.forEach { (id, text) ->
                assertEquals(text, storage.messages.readContent(storage.messages.resolve(id)))
            }
            assertEquals(checkpoint, ContextCompaction.checkpoint(storage, storage.messages.listBySession("s")))
            assertEquals("FAILED", storage.modelCalls.resolve("retry-summary").state)
            assertEquals("RUNNING", storage.modelCalls.resolve("resume-original").state)
            assertEquals(TurnState.WAITING_MODEL.name, storage.turns.resolve(coordinator.id).state)
        }
    }

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
                        ProviderContextSettings(),
                        true,
                        coordinator.id,
                    ),
                )
            val stream = kotlinx.coroutines.runBlocking { coordinator.beginModelStream(compacting = true) }
            stream.apply(ModelEvent.TextDelta("Keep the original constraints and unresolved work."))
            stream.apply(ModelEvent.Completed("stop"))
            kotlinx.coroutines.runBlocking { coordinator.commitCompaction(plan, null) }
            coordinator.settleFixtureTerminal(ModelStreamTerminal(TurnState.COMPLETED, null))
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
                        ProviderContextSettings(),
                        true,
                        coordinator.id,
                    ),
                )
            val before = storage.messages.listBySession("s").size
            kotlinx.coroutines
                .runBlocking {
                    coordinator.beginModelStream(compacting = true)
                }.apply(ModelEvent.TextDelta("unfinished summary"))
            assertThrows(IllegalArgumentException::class.java) {
                kotlinx.coroutines.runBlocking { coordinator.commitCompaction(plan, null) }
            }
            coordinator.settleFixtureTerminal(ModelStreamTerminal(TurnState.CANCELLED, null))
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
                    ProviderContextSettings(),
                    false,
                    coordinator.id,
                ),
            )
            val low = ProviderContextSettings(manualWindow = 8192, triggerPercent = 10)
            assertNotNull(ContextCompaction.plan(storage, "s", request, low, false, coordinator.id))
            assertNull(
                ContextCompaction.plan(
                    storage,
                    "s",
                    request,
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
                        ProviderContextSettings(),
                        true,
                        coordinator.id,
                    ),
                )
            val stream = kotlinx.coroutines.runBlocking { coordinator.beginModelStream(compacting = true) }
            stream.apply(ModelEvent.TextDelta("Concise earlier history."))
            stream.apply(ModelEvent.Completed("stop"))
            kotlinx.coroutines.runBlocking { coordinator.commitCompaction(plan, "next-call") }
            assertEquals(2, storage.modelCalls.listByTurn(coordinator.id).size)
            assertEquals(TurnState.WAITING_MODEL.name, storage.turns.resolve(coordinator.id).state)
            val reply = kotlinx.coroutines.runBlocking { coordinator.beginModelStream() }
            reply.apply(ModelEvent.TextDelta("The requested answer."))
            reply.apply(ModelEvent.Completed("stop"))
            coordinator.settleFixtureTerminal(ModelStreamTerminal(TurnState.COMPLETED, null))
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
                        ProviderContextSettings(),
                        true,
                        coordinator.id,
                    ),
                )
            val stream = kotlinx.coroutines.runBlocking { coordinator.beginModelStream(compacting = true) }
            stream.apply(ModelEvent.TextDelta("Durable constraints."))
            stream.apply(ModelEvent.Completed("stop"))
            kotlinx.coroutines.runBlocking { coordinator.commitCompaction(plan, "pending-after-summary") }
            val checkpoint = ContextCompaction.checkpoint(storage, storage.messages.listBySession("s"))
            val recovery =
                com.helix.app.infrastructure.agent
                    .TurnRecovery(storage, clock)
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
                        ProviderContextSettings(),
                        true,
                        coordinator.id,
                    ),
                )
            val stream = kotlinx.coroutines.runBlocking { coordinator.beginModelStream(compacting = true) }
            stream.apply(ModelEvent.TextDelta("Incomplete notes."))
            stream.apply(ModelEvent.Refusal("Refused"))
            stream.apply(ModelEvent.Completed("stop"))
            assertThrows(IllegalArgumentException::class.java) {
                kotlinx.coroutines.runBlocking { coordinator.commitCompaction(plan, null) }
            }
            coordinator.settleFixtureTerminal(stream.terminal(false))
            assertNull(ContextCompaction.checkpoint(storage, storage.messages.listBySession("s")))
            assertEquals("REFUSAL", storage.turns.resolve(coordinator.id).errorCode)
        }
    }

    @Test fun completedManualSummaryCanContinueWithAcceptedSteeringWithoutSavingSummaryAsChatText() {
        withStorage { storage ->
            val coordinator = seed(storage)
            val plan =
                requireNotNull(
                    ContextCompaction.plan(
                        storage,
                        "s",
                        request(storage),
                        ProviderContextSettings(),
                        true,
                        coordinator.id,
                    ),
                )
            val stream = kotlinx.coroutines.runBlocking { coordinator.beginModelStream(compacting = true) }
            stream.apply(ModelEvent.TextDelta("Compact continuity notes."))
            stream.apply(ModelEvent.Completed("stop"))
            val accepted =
                storage.sessionInputs.accept(
                    com.helix.core.model.input.SessionInputSpec(
                        "manual-steer",
                        "s",
                        com.helix.core.model.input.SessionInputDelivery.STEER,
                        coordinator.id,
                        0,
                        "Use the compacted context",
                        emptyList(),
                        com.helix.core.model.input
                            .InputConfiguration("provider", "model", "ACT", "fingerprint"),
                        2_000,
                    ),
                ) as com.helix.core.storage.repository.SessionInputAcceptResult.Accepted
            kotlinx.coroutines.runBlocking { coordinator.commitCompaction(plan, null, "Compacted") }
            assertEquals(
                com.helix.core.agent.ResponseInputBoundary.RECHECK,
                coordinator.completeResponseOrContinueFixture(null, "after-manual"),
            )
            val draft =
                com.helix.app.agent
                    .TurnSteeringDraft(accepted.record, "Use the compacted context", emptyList())
            assertEquals(
                com.helix.core.agent.ResponseInputBoundary.CONTINUED,
                coordinator.completeResponseOrContinueFixture(draft, "after-manual"),
            )
            assertEquals(TurnState.WAITING_MODEL, coordinator.snapshot().phase)
            assertTrue(
                storage.messages.listBySession("s").none {
                    it.kind == "TEXT" && storage.messages.readContent(it) == "Compact continuity notes."
                },
            )
            assertEquals(
                "Use the compacted context",
                storage.messages.readContent(storage.messages.listBySession("s").last()),
            )
        }
    }

    private fun seed(storage: HelixStorage): TurnRoundCoordinator {
        storage.sessions.create("s", "Compaction fixture", null, null, 1000)
        repeat(3) { index ->
            val old =
                startFixtureTurn(
                    storage,
                    clock,
                    ::next,
                    TurnStartSpec("s", "old-$index", next(), "{}", "constraint-$index " + "context ".repeat(600)),
                )
            seedToolPair(storage, "old-$index", index)
            val stream = kotlinx.coroutines.runBlocking { old.beginModelStream() }
            stream.apply(ModelEvent.TextDelta("answer-$index"))
            stream.apply(ModelEvent.Completed("stop"))
            old.settleFixtureTerminal(ModelStreamTerminal(TurnState.COMPLETED, null))
        }
        return startFixtureTurn(
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
        TurnContextRequest(
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
