package com.helix.app.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.app.provider.ProviderContextSettings
import com.helix.app.runcontrol.RunControlConfig
import com.helix.core.agent.GoalWakeReason
import com.helix.core.model.AgentMode
import com.helix.core.model.Clock
import com.helix.core.model.GoalBudgets
import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelRole
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

class LongTurnCompactionDeviceTest {
    private val clock =
        object : Clock {
            override fun now() = Instant.now()
        }
    private val control = RunControlConfig(AgentMode.GOAL, false, TurnBudgets(20, 20, 65536, 4096, 200000))
    private val settings = ProviderContextSettings(manualWindow = 8192, triggerPercent = 30)

    private fun next() = UUID.randomUUID().toString()

    @Test
    @Suppress("LongMethod") // One durable Goal lifecycle: summary accounting, parking, then immediate explicit wake.
    fun goalLongTurnCompactsSettledStepsAndNextWakeChecksImmediately() =
        withStorage { storage ->
            val goals = GoalRunCoordinator(storage, clock, ::next)
            val goal =
                goals.create(
                    "Keep ORANGE-42 and finish verification",
                    listOf("Verified result"),
                    GoalBudgets(40, 40, 400000, 600000, 300000, 1),
                )
            val stored = storage.goals.resolve(goal)
            storage.goals.updateGoal(
                stored.copy(
                    criteria =
                        stored.criteria.map {
                            it.copy(
                                binding =
                                    com.helix.core.model.CriterionVerificationBinding(
                                        com.helix.core.model.CriterionVerificationMethod.LOCAL_TOOL_SUCCESS,
                                        "read",
                                    ),
                            )
                        },
                ),
            )
            val first =
                requireNotNull(
                    goals.start(
                        GoalTurnStart(
                            goal,
                            GoalWakeReason.USER_OPEN,
                            TurnStartSpec("s", next(), next(), "{}", "Keep ORANGE-42; do not delete files"),
                            control.budgets,
                        ),
                    ),
                )
            val coordinator = first.coordinator
            repeat(4) { batch(storage, coordinator.id, it) }
            val originals = storage.messages.listBySession("s").associate { it.id to storage.messages.readContent(it) }
            val round = ContextCompactionRound(storage, "s", coordinator.id, control, settings, false)
            val prepared = round.prepare(request(storage))
            val plan = requireNotNull(prepared.plan)
            val kept = plan.retainedRequest.messages.mapNotNull { it.toolCallId?.value }
            assertEquals("call-3", kept.last())
            assertTrue(kept.size in 1..3)
            assertEquals(
                kept,
                plan.retainedRequest.messages
                    .flatMap { it.toolCalls }
                    .map { it.id.value },
            )
            assertTrue(plan.retainedRequest.messages.any { it.text.contains("do not delete") })
            val budget =
                ModelLoopAdmission.prepare(
                    requireNotNull(prepared.request),
                    coordinator.id,
                    coordinator.snapshot().modelCallId,
                    TurnBudgetTracker(control.budgets),
                    GoalModelCallBudget(storage, clock),
                )
            assertNull(budget.failure)
            val stream = coordinator.beginModelStream(true)
            stream.apply(ModelEvent.TextDelta("ORANGE-42; no deletion. Verified steps 0–2. Next: step 3."))
            stream.apply(ModelEvent.Completed("stop"))
            assertNull(budget.finish(stream))
            runBlocking {
                assertNull(round.finish(plan, stream, stream.terminal(false), coordinator, next(), "Compacted"))
            }
            assertTrue(storage.goals.resolve(goal).modelCalls >= 1)
            assertEquals("RUNNING", storage.goals.resolve(goal).state)
            val checkpoint = requireNotNull(ContextCompaction.checkpoint(storage, storage.messages.listBySession("s")))
            assertTrue(checkpoint.preservedMessageIds.isNotEmpty())
            assertEquals(kept, request(storage).messages.mapNotNull { it.toolCallId?.value })
            originals.forEach { (id, text) ->
                assertEquals(text, storage.messages.readContent(storage.messages.resolve(id)))
            }
            coordinator.beginModelStream().apply {
                apply(ModelEvent.TextDelta("Step complete"))
                apply(ModelEvent.Completed("stop"))
            }
            coordinator.terminalize(ModelStreamTerminal(TurnState.COMPLETED, null))
            assertEquals("PAUSED", storage.goals.resolve(goal).state)
            val runs = storage.goalRuns.listByGoal(goal).size
            // Explicit next wake, without a sleep/idle window. Same synchronous pre-call check.
            val second =
                requireNotNull(
                    goals.start(
                        GoalTurnStart(
                            goal,
                            GoalWakeReason.USER_OPEN,
                            TurnStartSpec("s", next(), next(), "{}", "Continue immediately"),
                            control.budgets,
                        ),
                    ),
                )
            // The very first request of the new Turn must not require another completed tool step.
            assertNotNull(
                ContextCompactionRound(
                    storage,
                    "s",
                    second.coordinator.id,
                    control,
                    settings,
                    false,
                ).prepare(request(storage)).plan,
            )
            repeat(4) { batch(storage, second.coordinator.id, it + 4) }
            assertNotNull(
                ContextCompactionRound(
                    storage,
                    "s",
                    second.coordinator.id,
                    control,
                    settings,
                    false,
                ).prepare(request(storage)).plan,
            )
            assertEquals(runs + 1, storage.goalRuns.listByGoal(goal).size)
            second.coordinator.beginModelStream()
            second.coordinator.terminalize(ModelStreamTerminal(TurnState.FAILED, "CONTEXT_WINDOW_LIMIT"))
            assertEquals("BLOCKED", storage.goals.resolve(goal).state)
            assertEquals(runs + 1, storage.goalRuns.listByGoal(goal).size)
        }

    @Test fun unfinishedToolBatchAndLatestSettledBatchStayVerbatim() =
        withStorage { storage ->
            val current = start(storage)
            repeat(4) { batch(storage, current.id, it) }
            calls(storage, current.id, 4)
            val plan =
                requireNotNull(
                    ContextCompaction.plan(storage, "s", request(storage), control, settings, true, current.id),
                )
            val keptCalls =
                plan.retainedRequest.messages
                    .flatMap { it.toolCalls }
                    .map { it.id.value }
            val keptResults = plan.retainedRequest.messages.mapNotNull { it.toolCallId?.value }
            assertEquals(listOf("call-3", "call-4"), keptCalls.takeLast(2))
            assertFalse("call-0" in keptCalls)
            assertEquals(keptCalls.dropLast(1), keptResults)
        }

    @Test fun expansionIsRejectedTwiceThenSoftPressureUsesOriginalHistory() =
        withStorage { storage ->
            val current = start(storage)
            repeat(4) { batch(storage, current.id, it) }
            val original = request(storage)
            val round = ContextCompactionRound(storage, "s", current.id, control, settings, false)
            repeat(2) {
                val plan = requireNotNull(round.prepare(original).plan)
                val stream = current.beginModelStream(true)
                stream.apply(ModelEvent.TextDelta("not a useful summary ".repeat(750)))
                stream.apply(ModelEvent.Completed("stop"))
                assertFalse(ContextCompaction.hasUsefulGain(plan, stream.text))
                runBlocking {
                    assertNull(round.finish(plan, stream, stream.terminal(false), current, next(), "Compacted"))
                }
            }
            assertNull(ContextCompaction.checkpoint(storage, storage.messages.listBySession("s")))
            val prepared = round.prepare(original)
            assertNull(prepared.plan)
            assertNull(prepared.failure)
            assertEquals(original.messages, prepared.request!!.messages)
            assertEquals(2, storage.modelCalls.listByTurn(current.id).count { it.state == "FAILED" })
        }

    @Test fun nonRetryableServerFailureCannotEnterCompactionRetry() =
        withStorage { storage ->
            val current = start(storage)
            repeat(4) { batch(storage, current.id, it) }
            val round = ContextCompactionRound(storage, "s", current.id, control, settings, false)
            val plan = requireNotNull(round.prepare(request(storage)).plan)
            val stream = current.beginModelStream(true)
            stream.apply(ModelEvent.Error(ModelErrorCode.SERVER_ERROR, false))
            runBlocking {
                assertEquals(
                    "SERVER_ERROR",
                    round.finish(plan, stream, stream.terminal(false), current, next(), "Compacted")?.errorCode,
                )
            }
            assertNull(ContextCompaction.checkpoint(storage, storage.messages.listBySession("s")))
            assertEquals(1, storage.modelCalls.listByTurn(current.id).size)
        }

    @Test fun actualStepUsageTriggersBeforeLocalEstimateAndIrreducibleInputFails() =
        withStorage { storage ->
            val current = start(storage)
            repeat(3) { batch(storage, current.id, it) }
            val original = request(storage)
            val round =
                ContextCompactionRound(
                    storage,
                    "s",
                    current.id,
                    control,
                    ProviderContextSettings(manualWindow = 32000, triggerPercent = 80),
                    false,
                )
            assertNull(round.prepare(original).plan)
            round.observe(original, 27000)
            assertNotNull(round.prepare(original).plan)
            val huge =
                original.copy(
                    messages =
                        listOf(
                            com.helix.core.model
                                .ModelMessage(ModelRole.USER, "x".repeat(50000)),
                        ),
                )
            assertEquals(
                "CONTEXT_WINDOW_LIMIT",
                ContextCompactionRound(storage, "s", current.id, control, settings, false)
                    .prepare(huge)
                    .failure
                    ?.errorCode,
            )
        }

    @Test fun repeatedStepCompactionKeepsCurrentInputAndAdvancesBoundary() =
        withStorage { storage ->
            val current = start(storage)
            var boundary = -1L
            repeat(3) { cycle ->
                repeat(4) { batch(storage, current.id, cycle * 4 + it) }
                val beforeCount = request(storage).messages.count { it.toolCallId != null }
                val plan =
                    requireNotNull(
                        ContextCompaction.plan(storage, "s", request(storage), control, settings, true, current.id),
                    )
                assertTrue(plan.coveredThrough > boundary)
                boundary = plan.coveredThrough
                val stream = current.beginModelStream(true)
                stream.apply(ModelEvent.TextDelta("ORANGE-42. No deletion. Steps through ${cycle * 4 + 2} verified."))
                stream.apply(ModelEvent.Completed("stop"))
                current.commitCompaction(plan, next())
                val messages = request(storage).messages
                assertTrue(messages.any { it.role == ModelRole.USER && it.text.contains("do not delete") })
                val remaining = messages.mapNotNull { it.toolCallId?.value }
                assertEquals("call-${cycle * 4 + 3}", remaining.last())
                assertEquals(plan.retainedRequest.messages.mapNotNull { it.toolCallId?.value }, remaining)
                assertTrue(remaining.size < beforeCount)
            }
        }

    @Test fun sparseCheckpointSurvivesDatabaseReopen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "sparse-${next()}.db"
        val directory = File(context.filesDir, name)
        var storage = HelixStorage.open(context, name, directory)
        try {
            storage.sessions.create("s", "Sparse fixture", null, null, 1000)
            val current = start(storage)
            repeat(4) { batch(storage, current.id, it) }
            val plan =
                requireNotNull(
                    ContextCompaction.plan(storage, "s", request(storage), control, settings, true, current.id),
                )
            current.beginModelStream(true).apply {
                apply(ModelEvent.TextDelta("ORANGE-42; never delete original files."))
                apply(ModelEvent.Completed("stop"))
            }
            current.commitCompaction(plan, next())
            val before = request(storage)
            storage.close()
            storage = HelixStorage.open(context, name, directory)
            assertEquals(before, request(storage))
            assertTrue(request(storage).messages.any { it.role == ModelRole.USER })
        } finally {
            storage.close()
            context.deleteDatabase(name)
            directory.deleteRecursively()
        }
    }

    @Test fun exhaustedCompactionAttemptsNeverSendAnOversizeOriginalRequest() =
        withStorage { storage ->
            val current = start(storage)
            repeat(4) { batch(storage, current.id, it) }
            val round =
                ContextCompactionRound(
                    storage,
                    "s",
                    current.id,
                    control,
                    ProviderContextSettings(manualWindow = 4096, triggerPercent = 30),
                    false,
                )
            repeat(2) { attempt ->
                val plan = requireNotNull(round.prepare(request(storage)).plan)
                val stream = current.beginModelStream(true)
                stream.apply(ModelEvent.TextDelta("not useful ".repeat(1450)))
                stream.apply(ModelEvent.Completed("stop"))
                val terminal =
                    runBlocking { round.finish(plan, stream, stream.terminal(false), current, next(), "Compacted") }
                if (attempt == 0) assertNull(terminal) else assertEquals("CONTEXT_WINDOW_LIMIT", terminal?.errorCode)
            }
            assertNull(ContextCompaction.checkpoint(storage, storage.messages.listBySession("s")))
        }

    private fun start(storage: HelixStorage) =
        TurnCoordinator.start(
            storage,
            clock,
            ::next,
            TurnStartSpec("s", next(), next(), "{}", "Keep ORANGE-42; do not delete files"),
        )

    private fun calls(
        storage: HelixStorage,
        turn: String,
        index: Int,
    ) {
        storage.messages.append(
            next(),
            "s",
            turn,
            "ASSISTANT",
            ChatHistoryBuilder.KIND_TOOL_CALLS,
            """[{"id":"call-$index","name":"time.now","arguments":"{}"}]""",
        )
    }

    private fun batch(
        storage: HelixStorage,
        turn: String,
        index: Int,
    ) {
        calls(storage, turn, index)
        val content =
            """{"id":"call-$index","tool":"time.now","status":"COMPLETED","summary":"""" +
                "verified log ".repeat(400) + "\"}"
        storage.messages.append(next(), "s", turn, "TOOL", ChatHistoryBuilder.KIND_TOOL_RESULT, content)
    }

    private fun request(storage: HelixStorage): ChatContextRequest {
        val rows = storage.messages.listBySession("s")
        val checkpoint = ContextCompaction.checkpoint(storage, rows)
        val messages = ContextCompaction.retained(rows, checkpoint).flatMap { ContextSegments.mapped(storage, it) }
        return ChatContextRequest(
            "fixture",
            checkpoint?.let { listOf(ContextCompaction.summaryMessage(it)) }.orEmpty() + messages,
            emptyList(),
            512,
            ReasoningEffort.OFF,
        )
    }

    private fun withStorage(block: (HelixStorage) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "long-context-${next()}.db"
        val directory = File(context.filesDir, name)
        val storage = HelixStorage.open(context, name, directory)
        try {
            storage.sessions.create("s", "Long turn fixture", null, null, 1000)
            block(storage)
        } finally {
            storage.close()
            context.deleteDatabase(name)
            directory.deleteRecursively()
        }
    }
}
