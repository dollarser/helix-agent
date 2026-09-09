package com.helix.app.ui

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.helix.app.MainActivity
import com.helix.app.foreground.DataSyncForegroundService
import com.helix.app.provider.LoopbackModelServer
import com.helix.app.provider.ProviderDraft
import com.helix.core.model.AgentMode
import com.helix.core.model.GoalBudgets
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.provider.api.CleartextAuthorization
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BackgroundTaskFlowDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun anotherSessionCompletesWhileFirstRunsAndOldCancelCannotStopNewTurn() = exercise(false)

    @Test fun pauseGoalFromTaskListKeepsItsContinuePath() = exercise(true)

    @Suppress("LongMethod", "CyclomaticComplexMethod") // Shared two-session lifecycle fixture and teardown.
    private fun exercise(goal: Boolean) =
        runBlocking {
            compose.resetDeterministicUiState()
            val container = compose.container()
            val chat = container.chatService
            val storage = container.storage
            val previous = chat.runControl.value
            LoopbackModelServer(LoopbackModelServer.Mode.OPENAI_LISTED).use { server ->
                server.start()
                LoopbackModelServer(LoopbackModelServer.Mode.OPENAI_LISTED).use { secondServer ->
                    secondServer.start()
                    val provider =
                        container.providerService.create(
                            ProviderDraft(
                                null,
                                "Background fixture",
                                ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                                NormalizedEndpoint.parse("http://127.0.0.1:${server.port}/v1"),
                                "fixture-model-a",
                                "{}",
                                false,
                                CleartextAuthorization("127.0.0.1", server.port),
                                emptyList(),
                            ),
                            null,
                            cleartextConfirmed = true,
                        )
                    check(container.providerService.runConnectionTest(provider) is ProbeOutcome.Ok)
                    server.forceTextResponses = true
                    val secondProvider =
                        container.providerService.create(
                            ProviderDraft(
                                null,
                                "Background fixture",
                                ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                                NormalizedEndpoint.parse("http://127.0.0.1:${secondServer.port}/v1"),
                                "fixture-model-a",
                                "{}",
                                false,
                                CleartextAuthorization("127.0.0.1", secondServer.port),
                                emptyList(),
                            ),
                            null,
                            cleartextConfirmed = true,
                        )
                    check(container.providerService.runConnectionTest(secondProvider) is ProbeOutcome.Ok)
                    secondServer.forceTextResponses = true
                    val first = chat.createSession("Background first", provider, "fixture-model-a")
                    val second = chat.createSession("Background second", secondProvider, "fixture-model-a")
                    try {
                        chat.openSession(first)
                        compose.waitUntil(10000) { chat.screen.value.openSessionId == first }
                        chat.setMode(if (goal) AgentMode.GOAL else AgentMode.CHAT)
                        server.holdChatStreams.set(true)
                        val goalId =
                            if (goal) {
                                chat.createGoal(
                                    "Fixture",
                                    listOf("Evidence"),
                                    GoalBudgets(10, 10, 100000, 600000, 300000, 0),
                                )
                            } else {
                                null
                            }
                        if (goalId == null) chat.send("First input") else chat.continueGoal(goalId, "First input")
                        compose.waitUntil(
                            10000,
                        ) { chat.backgroundTasks.value.any { it.sessionId == first && it.running } }
                        val firstTurn =
                            chat.backgroundTasks.value
                                .first { it.sessionId == first }
                                .id
                        // Give the held request time to enter the fixture before subsequent requests are released.
                        compose.waitUntil(10000) { server.lastChatRequest.get()?.contains("First input") == true }
                        chat.openSession(second)
                        compose.waitUntil(
                            10000,
                        ) { chat.screen.value.openSessionId == second && !chat.screen.value.isSending }
                        assertTrue(chat.backgroundTasks.value.any { it.id == firstTurn && it.running })
                        compose.waitUntil(10000) { DataSyncForegroundService.runningInstance.get() != null }
                        if (goalId != null) {
                            compose.onNodeWithTag("background-tasks-open").performClick()
                            compose.onNodeWithTag("task-pause-$firstTurn").performClick()
                            compose.waitUntil(10000) { storage.goals.resolve(goalId).state == "PAUSED" }
                            assertEquals(
                                "USER_PAUSED",
                                storage.goalRuns
                                    .listByGoal(goalId)
                                    .single()
                                    .outcome,
                            )
                            assertEquals(1, storage.goalRuns.listByGoal(goalId).size)
                        } else {
                            chat.send("Second input")
                            compose.waitUntil(10000) {
                                storage.turns
                                    .listBySession(second)
                                    .lastOrNull()
                                    ?.state == "COMPLETED"
                            }
                            assertEquals(second, chat.screen.value.openSessionId)
                            assertTrue(chat.backgroundTasks.value.any { it.id == firstTurn && it.running })
                            val done = storage.turns.listBySession(second).last()
                            // Turn persistence precedes the asynchronous task-list projection.
                            compose.waitUntil(10000) {
                                chat.backgroundTasks.value.any { it.id == done.id && !it.running }
                            }
                            compose.onNodeWithTag("background-tasks-open").performClick()
                            compose.onNodeWithTag("task-tab-1").performClick()
                            compose.onNodeWithTag("task-result-${done.id}").performClick()
                            compose.waitUntil(10000) {
                                val node = compose.onNodeWithTag("task-collect-${done.id}").fetchSemanticsNode()
                                !node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
                            }
                            compose.onNodeWithTag("task-collect-${done.id}").performClick()
                            compose.waitUntil(10000) { storage.turns.resolve(done.id).resultCollectedAt != null }
                            chat.stopTask(firstTurn)
                            compose.waitUntil(10000) { storage.turns.resolve(firstTurn).state == "CANCELLED" }
                            chat.openSession(first)
                            compose.waitUntil(10000) { chat.screen.value.openSessionId == first }
                            server.holdChatStreams.set(true)
                            chat.send("Third input")
                            compose.waitUntil(10000) {
                                chat.backgroundTasks.value.any {
                                    it.sessionId == first && it.id != firstTurn &&
                                        it.running
                                }
                            }
                            chat.stopTask(firstTurn)
                            assertFalse(
                                storage.turns
                                    .listBySession(first)
                                    .last()
                                    .state == "CANCELLED",
                            )
                        }
                    } finally {
                        chat.backgroundTasks.value
                            .filter { it.sessionId in setOf(first, second) && it.running }
                            .forEach { chat.stopTask(it.id) }
                        compose.waitUntil(10000) {
                            chat.backgroundTasks.value.none { it.sessionId in setOf(first, second) && it.running }
                        }
                        chat.closeSession()
                        chat.setMode(previous.mode)
                        chat.setTurnBudgets(previous.budgets)
                        container.providerService.delete(provider)
                        container.providerService.delete(secondProvider)
                    }
                }
            }
        }
}
