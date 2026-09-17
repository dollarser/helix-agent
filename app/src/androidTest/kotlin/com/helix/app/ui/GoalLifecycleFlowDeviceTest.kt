package com.helix.app.ui

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.helix.app.MainActivity
import com.helix.app.foreground.DataSyncForegroundService
import com.helix.app.provider.LoopbackModelServer
import com.helix.app.provider.ProviderDraft
import com.helix.core.model.AgentMode
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.provider.api.CleartextAuthorization
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real Room, dispatcher, SSE provider, runtime and foreground service; no external network. */
class GoalLifecycleFlowDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun goalContinuesIntoSecondRoundWhileActivityIsStopped() =
        runBlocking {
            fixture(AgentMode.GOAL, createWithTool = false, background = true)
        }

    @Test fun modelCreateStartsBoundGoalAndUpdateCompletesIt() =
        runBlocking {
            fixture(AgentMode.ACT, createWithTool = true, background = false)
        }

    @Test fun humanMessagePreemptsWithoutResettingGoalOrBudget() =
        runBlocking {
            fixture(AgentMode.GOAL, createWithTool = false, background = false, steer = true)
        }

    // Three journeys share one owned lifecycle fixture.
    @Suppress("LongMethod", "NestedBlockDepth", "CyclomaticComplexMethod")
    private suspend fun fixture(
        mode: AgentMode,
        createWithTool: Boolean,
        background: Boolean,
        steer: Boolean = false,
    ) {
        compose.resetDeterministicUiState()
        val container = compose.container()
        val chat = container.chatService
        val previous = chat.runControl.value
        LoopbackModelServer(LoopbackModelServer.Mode.OPENAI_LISTED).use { server ->
            server.start()
            val provider =
                container.providerService.create(
                    ProviderDraft(
                        null,
                        "Goal lifecycle fixture",
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
            var session: String? = null
            val release = CountDownLatch(if (background || steer) 1 else 0)
            try {
                check(container.providerService.runConnectionTest(provider) is ProbeOutcome.Ok)
                val steps = AtomicInteger()
                val services = java.util.concurrent.CopyOnWriteArrayList<DataSyncForegroundService?>()
                server.scriptedChat = { request ->
                    services.add(DataSyncForegroundService.runningInstance.get())
                    val step = steps.getAndIncrement()
                    check(release.await(30, TimeUnit.SECONDS))
                    if (steer &&
                        step == 0
                    ) {
                        answer()
                    } else {
                        response(
                            if (steer) step + 1 else step,
                            request,
                            createWithTool || steer,
                        )
                    }
                }
                session = chat.createSession("Goal lifecycle fixture", provider, "fixture-model-a")
                chat.openSession(session)
                await { chat.screen.value.openSessionId == session }
                chat.setMode(mode)
                // This lifecycle fixture owns its model/tool budget; earlier UI tests
                // may intentionally persist a tiny token limit.
                chat.setTurnBudgets(
                    com.helix.core.model
                        .TurnBudgets(16, 16, 65536, 4096, 100000),
                )
                chat.send("Please create a goal and answer 2 + 2.")
                await { DataSyncForegroundService.runningInstance.get() != null }
                val firstService = DataSyncForegroundService.runningInstance.get()
                if (background) {
                    val activity = compose.activity
                    compose.runOnUiThread { activity.moveTaskToBack(true) }
                    await { activity.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.CREATED }
                }
                if (steer) {
                    await { steps.get() == 1 }
                    chat.send("Please keep the existing goal and answer now.")
                    await {
                        container.storage.turns
                            .listBySession(session)
                            .first()
                            .pauseRequestedAt != null
                    }
                }
                release.countDown()
                val ownedSession = session
                await {
                    container.storage.goalControls.bySession(ownedSession).any {
                        container.storage.goals
                            .resolve(it.goalId)
                            .state == "COMPLETED"
                    }
                }
                val control =
                    container.storage.goalControls
                        .bySession(session)
                        .single()
                val runs = container.storage.goalRuns.listByGoal(control.goalId)
                assertEquals(if (createWithTool) 1 else 2, runs.size)
                assertEquals("MODEL_COMPLETED", runs.last().outcome)
                if (steer) {
                    assertEquals("USER_PAUSED", runs.first().outcome)
                    assertTrue(runs.first().modelCalls > 0)
                    assertEquals(
                        runs.sumOf { it.modelCalls },
                        container.storage.goals
                            .resolve(control.goalId)
                            .modelCalls,
                    )
                }
                assertTrue(runs.all { it.endedAt != null })
                assertTrue(
                    container.storage.goals
                        .resolve(control.goalId)
                        .modelCalls >= 3,
                )
                val calls =
                    container.storage.turns.listBySession(session).flatMap {
                        container.storage.toolCalls.listByTurn(it.id)
                    }
                assertTrue(calls.any { it.name == "get_goal" && it.state == "COMPLETED" })
                assertTrue(calls.any { it.name == "update_goal" && it.state == "COMPLETED" })
                if (createWithTool) assertTrue(calls.any { it.name == "create_goal" && it.state == "COMPLETED" })
                if (background) {
                    assertNotNull(firstService)
                    services.drop(1).forEach { assertSame("FGS must bridge the round boundary", firstService, it) }
                    assertEquals("FOREGROUND_CONTINUATION", runs.last().wakeReason)
                }
                await { DataSyncForegroundService.runningInstance.get() == null }
            } finally {
                release.countDown()
                chat.stopContinuousGoals()
                await { chat.backgroundTasks.value.none { it.sessionId == session && it.running } }
                chat.closeSession()
                chat.setMode(previous.mode)
                chat.setTurnBudgets(previous.budgets)
                container.providerService.delete(provider)
            }
        }
    }

    @Suppress("ReturnCount") // Scripted create, handoff and reporting phases.
    private fun response(step: Int, request: String, create: Boolean): String {
        if (create && step == 0) {
            return tool(
                "create_goal",
                buildJsonObject {
                    put("objective", "Answer 2 + 2")
                    put("user_request", "Please create a goal and answer 2 + 2.")
                },
                step,
            )
        }
        if (create && step == 1) return answer()
        val roundStep = if (create) step - 2 else step
        return when (roundStep % 3) {
            0 -> {
                tool("get_goal", buildJsonObject {}, step)
            }

            1 -> {
                val messages =
                    Json
                        .parseToJsonElement(request)
                        .jsonObject
                        .getValue("messages")
                        .jsonArray
                val content =
                    messages
                        .last()
                        .jsonObject
                        .getValue("content")
                        .jsonPrimitive.content
                val result = Json.parseToJsonElement(content.removePrefix("[SUCCEEDED] ")).jsonObject
                val goal = findGoal(result)
                tool(
                    "update_goal",
                    buildJsonObject {
                        put("id", goal.getValue("id"))
                        put("expected_revision", goal.getValue("revision"))
                        put("status", if (create || roundStep >= 3) "complete" else "in_progress")
                        put("summary", "Answer checked: 2 + 2 = 4")
                    },
                    step,
                )
            }

            else -> {
                answer()
            }
        }
    }

    @Suppress("ReturnCount") // Accept the real model result and older persisted fixture envelopes.
    private fun findGoal(result: JsonObject): JsonObject {
        result["goal"]?.let { return it.jsonObject }
        result["summary"]?.let {
            return Json
                .parseToJsonElement(it.jsonPrimitive.content)
                .jsonObject
                .getValue("goal")
                .jsonObject
        }
        return result.values.filterIsInstance<JsonObject>().firstNotNullOf { nested ->
            nested["goal"]?.jsonObject
        }
    }

    private fun tool(
        name: String,
        arguments: JsonObject,
        step: Int,
    ): String {
        val function =
            buildJsonObject {
                put("name", name)
                put("arguments", arguments.toString())
            }
        return "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[" +
            "{\"id\":\"goal-$step\",\"index\":0,\"type\":\"function\",\"function\":$function}]}," +
            "\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n"
    }

    private fun answer(): String =
        "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"4\"}," +
            "\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"

    private suspend fun await(condition: () -> Boolean) =
        withContext(Dispatchers.IO) {
            withTimeout(60_000) { while (!condition()) delay(50) }
        }
}
