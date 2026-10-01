package com.helix.app.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.agent.ChatHistoryBuilder
import com.helix.app.agent.TurnCoordinator
import com.helix.app.agent.TurnStartSpec
import com.helix.app.chat.GoalRunCoordinator
import com.helix.app.review.ToolReviewSubmission
import com.helix.app.review.TurnReviewResolutionResult
import com.helix.core.agent.GoalWakeReason
import com.helix.core.agent.RunControlConfig
import com.helix.core.agent.TurnMessageDraft
import com.helix.core.model.AgentMode
import com.helix.core.model.Clock
import com.helix.core.model.GoalBudgets
import com.helix.core.model.GoalState
import com.helix.core.model.ModelRole
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.ToolCallState
import com.helix.core.model.ToolEffectReviewDecision
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.repository.ProviderConfigSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TurnReviewResolutionDeviceTest {
    @Test
    fun deterministicReviewClosesOldTurnAndGoalRunWithoutOpeningAnotherModelCall() =
        runBlocking {
            val storage = isolatedStorage()
            try {
                val fixture = createGoalFixture(storage)
                completeFirstRound(storage, fixture.coordinator)
                parkReviewBatch(storage, fixture.coordinator)
                val beforeModelCalls = storage.modelCalls.listByTurn(TURN).map { it.id }
                val beforeToolMessages = toolResultMessageIds(storage)

                val resolution = TurnReviewResolution(storage, fixture.clock, fixture.ids)
                val command =
                    ReviewResolutionCommand(
                        turnId = TURN,
                        clientActionId = "review-action-1",
                        expectedTurnState = TurnState.NEEDS_REVIEW,
                        submissions =
                            listOf(
                                ToolReviewSubmission(
                                    LOCAL_UNKNOWN,
                                    ToolEffectReviewDecision.CONFIRMED_NOT_APPLIED,
                                    2_500L,
                                ),
                            ),
                    )

                assertTrue(resolution.resolve(command) is TurnReviewResolutionResult.Resolved)
                assertResolvedAttempt(
                    storage,
                    fixture,
                    beforeModelCalls,
                    beforeToolMessages,
                    "INTERRUPTED(REVIEW_RESOLVED)",
                )

                assertTrue(resolution.resolve(command) is TurnReviewResolutionResult.Resolved)
                assertResolvedAttempt(
                    storage,
                    fixture,
                    beforeModelCalls,
                    beforeToolMessages,
                    "INTERRUPTED(REVIEW_RESOLVED)",
                )
            } finally {
                storage.close()
            }
        }

    @Test
    fun acknowledgedUnknownClosesOldTurnWithoutCancellingOrReplaying() =
        runBlocking {
            val storage = isolatedStorage()
            try {
                val fixture = createGoalFixture(storage)
                parkReviewBatch(storage, fixture.coordinator)
                val beforeModelCalls = storage.modelCalls.listByTurn(TURN).map { it.id }
                val beforeToolMessages = toolResultMessageIds(storage)

                val result =
                    TurnReviewResolution(storage, fixture.clock, fixture.ids).resolve(
                        ReviewResolutionCommand(
                            turnId = TURN,
                            clientActionId = "review-unknown-1",
                            expectedTurnState = TurnState.NEEDS_REVIEW,
                            submissions =
                                listOf(
                                    ToolReviewSubmission(
                                        LOCAL_UNKNOWN,
                                        ToolEffectReviewDecision.ACKNOWLEDGED_UNKNOWN,
                                        2_500L,
                                    ),
                                ),
                        ),
                    )

                assertTrue(result is TurnReviewResolutionResult.Resolved)
                val turn = storage.turns.resolve(TURN)
                assertEquals(TurnState.INTERRUPTED.name, turn.state)
                assertEquals("EFFECT_STATE_ACKNOWLEDGED_UNKNOWN", turn.errorCode)
                assertEquals(beforeModelCalls, storage.modelCalls.listByTurn(TURN).map { it.id })
                assertEquals(beforeToolMessages, toolResultMessageIds(storage))
                assertEquals(GoalState.PAUSED.name, storage.goals.resolve(fixture.goalId).state)
                assertEquals(
                    "INTERRUPTED(REVIEW_ACKNOWLEDGED_UNKNOWN)",
                    storage.goalRuns.resolve(fixture.bindingRunId).outcome,
                )
                assertEquals(
                    ToolEffectReviewDecision.ACKNOWLEDGED_UNKNOWN.name,
                    storage.toolCallReviews.findByToolCallId(LOCAL_UNKNOWN)?.decision,
                )
                val unknown = storage.toolCalls.resolve(LOCAL_UNKNOWN)
                assertEquals(ToolCallState.NEEDS_REVIEW.name, unknown.state)
                assertEquals(UNKNOWN_INTENT, unknown.modelIntent)
            } finally {
                storage.close()
            }
        }

    private fun assertResolvedAttempt(
        storage: HelixStorage,
        fixture: GoalFixture,
        modelCalls: List<String>,
        toolMessages: List<String>,
        runOutcome: String,
    ) {
        val turn = storage.turns.resolve(TURN)
        assertEquals(TurnState.INTERRUPTED.name, turn.state)
        assertEquals("EFFECT_REVIEW_RESOLVED", turn.errorCode)
        assertEquals(modelCalls, storage.modelCalls.listByTurn(TURN).map { it.id })
        assertEquals(toolMessages, toolResultMessageIds(storage))
        val unknown = storage.toolCalls.resolve(LOCAL_UNKNOWN)
        assertEquals(ToolCallState.NEEDS_REVIEW.name, unknown.state)
        assertEquals(UNKNOWN_INTENT, unknown.modelIntent)
        assertEquals("NEEDS_REVIEW", storage.toolResults.byToolCall(LOCAL_UNKNOWN)?.status)
        assertEquals(GoalState.PAUSED.name, storage.goals.resolve(fixture.goalId).state)
        assertEquals(fixture.runCount, storage.goals.resolve(fixture.goalId).runCount)
        val run = storage.goalRuns.resolve(fixture.bindingRunId)
        assertNotNull(run.endedAt)
        assertEquals(runOutcome, run.outcome)
    }

    private fun createGoalFixture(storage: HelixStorage): GoalFixture {
        val clock = FixedClock(2_000L)
        var nextId = 0
        val ids = { "review-id-" + nextId++ }
        // `sessions.providerId` is a real FK to `provider_configs` (SET NULL on delete), so the
        // provider row must exist before the session binds to it.
        storage.providerConfigs.save(
            ProviderConfigSpec(
                id = PROVIDER,
                displayName = "Review provider",
                protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                endpoint = "https://provider-review.invalid/v1",
                model = MODEL,
                headersJson = "{}",
                secretAlias = "review-provider-secret",
                capabilitySnapshot = "{}",
            ),
        )
        storage.sessions.create(SESSION, "Review", PROVIDER, MODEL, 1_000L)
        val goalBudgets = GoalBudgets(20, 20, 100_000, 100_000, 100_000, 1)
        val goalId = GoalRunCoordinator(storage, clock, ids).create("finish safely", emptyList(), goalBudgets)
        val control =
            RunControlConfig(
                AgentMode.GOAL,
                true,
                TurnBudgets(8, 10, 20_000, 4_000, 40_000),
                ReasoningEffort.LOW,
                goalBudgets,
            )
        val admitted =
            TurnAdmission(storage, clock, ids).start(
                TurnStartSpec(SESSION, TURN, MODEL_CALL_1, SNAPSHOT, "do work"),
                control = control,
                wakeReason = GoalWakeReason.USER_OPEN,
                providerId = PROVIDER,
                modelId = MODEL,
                resolveGoal = { goalId },
            ) as TurnAdmissionResult.Started
        val binding = requireNotNull(storage.goalTurnBindings.byTurn(TURN))
        return GoalFixture(
            clock,
            ids,
            goalId,
            admitted.turn.coordinator,
            binding.runId,
            storage.goals.resolve(goalId).runCount,
        )
    }

    private fun completeFirstRound(
        storage: HelixStorage,
        coordinator: TurnCoordinator,
    ) {
        coordinator.beginModelStream()
        coordinator.beginToolBatch(listOf(LOCAL_OLD))
        coordinator.commitModelToolStep(
            "[{\"id\":\"$WIRE_OLD\",\"localId\":\"$LOCAL_OLD\",\"name\":\"time.now\",\"arguments\":\"{}\"}]",
        )
        storage.toolCalls.append(LOCAL_OLD, TURN, LOCAL_OLD, "time.now", "1", "{}", ToolCallState.COMPLETED.name)
        storage.toolResults.append("result-old", LOCAL_OLD, "SUCCEEDED", "old-ok", null)
        coordinator.settleBatchCall(LOCAL_OLD, sideEffectUnknown = false)
        coordinator.openNextModelCall(
            listOf(
                TurnMessageDraft(
                    ModelRole.TOOL,
                    ChatHistoryBuilder.KIND_TOOL_RESULT,
                    "{\"id\":\"$WIRE_OLD\",\"tool\":\"time.now\",\"status\":\"SUCCEEDED\",\"summary\":\"old-ok\"}",
                ),
            ),
            MODEL_CALL_2,
        )
    }

    private fun parkReviewBatch(
        storage: HelixStorage,
        coordinator: TurnCoordinator,
    ) {
        coordinator.beginModelStream()
        coordinator.beginToolBatch(listOf(LOCAL_OK, LOCAL_UNKNOWN))
        coordinator.commitModelToolStep(
            "[{\"id\":\"$WIRE_OK\",\"localId\":\"$LOCAL_OK\",\"name\":\"time.now\"," +
                "\"arguments\":\"{}\"},{\"id\":\"$WIRE_UNKNOWN\",\"localId\":\"$LOCAL_UNKNOWN\"," +
                "\"name\":\"files.write\",\"arguments\":\"{}\"}]",
        )
        storage.toolCalls.append(LOCAL_OK, TURN, LOCAL_OK, "time.now", "1", "{}", ToolCallState.COMPLETED.name)
        storage.toolResults.append("result-ok", LOCAL_OK, "SUCCEEDED", "new-ok", null)
        storage.toolCalls.append(
            LOCAL_UNKNOWN,
            TURN,
            LOCAL_UNKNOWN,
            "files.write",
            "1",
            "{}",
            ToolCallState.NEEDS_REVIEW.name,
            UNKNOWN_INTENT,
        )
        storage.toolResults.append("result-unknown", LOCAL_UNKNOWN, "NEEDS_REVIEW", "effect uncertain", null)
        coordinator.settleBatchCall(LOCAL_OK, sideEffectUnknown = false)
        coordinator.settleBatchCall(LOCAL_UNKNOWN, sideEffectUnknown = true)
        coordinator.parkFixtureForReview(listOf(LOCAL_UNKNOWN))
    }

    private fun toolResultMessageIds(storage: HelixStorage): List<String> =
        storage.messages
            .listBySession(SESSION)
            .filter {
                it.turnId == TURN && it.role == ModelRole.TOOL.name && it.kind == ChatHistoryBuilder.KIND_TOOL_RESULT
            }.map { it.id }

    private fun isolatedStorage(): HelixStorage {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString()
        return HelixStorage.open(context, "turn-review-$suffix.db", File(context.filesDir, "turn-review-$suffix"))
    }

    private class FixedClock(
        private val millis: Long,
    ) : Clock {
        override fun now(): Instant = Instant.ofEpochMilli(millis)
    }

    private data class GoalFixture(
        val clock: Clock,
        val ids: () -> String,
        val goalId: String,
        val coordinator: TurnCoordinator,
        val bindingRunId: String,
        val runCount: Int,
    )

    private companion object {
        const val SESSION = "session-review"
        const val TURN = "turn-review"
        const val PROVIDER = "provider-review"
        const val UNKNOWN_INTENT = "Write requested file"
        const val MODEL = "model-review"
        const val SNAPSHOT = """{"providerId":"provider-review","model":"model-review"}"""
        const val MODEL_CALL_1 = "model-call-1"
        const val MODEL_CALL_2 = "model-call-2"
        const val LOCAL_OLD = "local-old"
        const val LOCAL_OK = "local-ok"
        const val LOCAL_UNKNOWN = "local-unknown"
        const val WIRE_OLD = "wire-old"
        const val WIRE_OK = "wire-ok"
        const val WIRE_UNKNOWN = "wire-unknown"
    }
}
