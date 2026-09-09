from pathlib import Path
p=Path('app/src/androidTest/kotlin/com/helix/app/provider/LoopbackModelServer.kt');s=p.read_text().replace('    var forceTextResponses = false','    var forceTextResponses = false\n\n    @Volatile var goalReportStatus: String? = null')
a=s.index('    private fun chatStream(requestBody: String): String =');b=s.index('    /** The Anthropic',a)
s=s[:a]+'''    private fun chatStream(requestBody: String): String {
        val report = goalReportStatus
        if (report != null) {
            if (requestBody.contains("\\\"role\\\":\\\"tool\\\"")) return OPENAI_TEXT_STREAM
            val arguments = kotlinx.serialization.json.buildJsonObject {
                put("status", kotlinx.serialization.json.JsonPrimitive(report))
                put("summary", kotlinx.serialization.json.JsonPrimitive("Checked the requested answer; no work remains."))
            }.toString()
            val function = kotlinx.serialization.json.buildJsonObject {
                put("name", kotlinx.serialization.json.JsonPrimitive("goal.report"))
                put("arguments", kotlinx.serialization.json.JsonPrimitive(arguments))
            }
            return "data: {\\\"id\\\":\\\"goal-fixture\\\",\\\"choices\\\":[{\\\"index\\\":0,\\\"delta\\\":{\\\"tool_calls\\\":[" +
                "{\\\"id\\\":\\\"report-call\\\",\\\"index\\\":0,\\\"type\\\":\\\"function\\\",\\\"function\\\":$function}]}," +
                "\\\"finish_reason\\\":\\\"tool_calls\\\"}]}\\n\\ndata: [DONE]\\n\\n"
        }
        return if (!forceTextResponses && requestBody.contains("\\\"tools\\\"")) OPENAI_TOOL_STREAM else OPENAI_TEXT_STREAM
    }

'''+s[b:];p.write_text(s)
p=Path('app/src/androidTest/kotlin/com/helix/app/ui/GoalModelReportFlowDeviceTest.kt');p.write_text('''package com.helix.app.ui

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.MainActivity
import com.helix.app.provider.LoopbackModelServer
import com.helix.app.provider.ProviderDraft
import com.helix.core.model.GoalBudgets
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.provider.api.CleartextAuthorization
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GoalModelReportFlowDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun openEndedGoalCompletesThroughTheRealDispatcher() = fixture("complete", "COMPLETED")
    @Test fun incompleteGoalRemainsResumable() = fixture("in_progress", "PAUSED")
    @Test fun modelBlockerIsDurableAndCanBeRechecked() = fixture("blocked", "BLOCKED")

    private fun fixture(report: String, expected: String) = runBlocking {
        LoopbackModelServer(LoopbackModelServer.Mode.OPENAI_LISTED).use { server ->
            server.start()
            runGoalReportFlow(compose, server.port, "fixture-model-a", expected) { server.goalReportStatus = report }
        }
    }
}

class LiveGoalModelReportDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun realModelCompletesAnUnboundGoal() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val port = requireNotNull(args.getString("goalReportPort")) { "Explicit live test port is required" }.toInt()
        val model = requireNotNull(args.getString("goalReportModel"))
        runGoalReportFlow(compose, port, model, "COMPLETED") {}
    }
}

@Suppress("LongMethod") // One end-to-end fixture includes provider/session ownership and cleanup.
private suspend fun runGoalReportFlow(
    compose: androidx.compose.ui.test.junit4.v2.AndroidComposeTestRule<androidx.test.ext.junit.rules.ActivityScenarioRule<MainActivity>, MainActivity>,
    port: Int,
    model: String,
    expected: String,
    afterProbe: () -> Unit,
) {
    compose.resetDeterministicUiState()
    val container = compose.container()
    val chat = container.chatService
    val storage = container.storage
    val previous = chat.runControl.value
    val provider = container.providerService.create(
        ProviderDraft(null, "Goal report fixture", ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
            NormalizedEndpoint.parse("http://127.0.0.1:$port/v1"), model, "{}", false,
            CleartextAuthorization("127.0.0.1", port), emptyList()),
        null, cleartextConfirmed = true,
    )
    var session: String? = null
    try {
        check(container.providerService.runConnectionTest(provider) is ProbeOutcome.Ok)
        afterProbe()
        session = chat.createSession("Goal report fixture", provider, model)
        chat.openSession(session)
        compose.waitUntil(10000) { chat.screen.value.openSessionId == session }
        val goal = chat.createGoal("Answer what 2 + 2 is", listOf("Give the correct answer clearly"),
            GoalBudgets(10, 10, 100000, 300000, 240000, 0))
        chat.continueGoal(goal, "Answer 2 + 2. This is the entire task. Report your Goal status using goal.report, then answer briefly.")
        compose.waitUntil(180000) {
            storage.goalRuns.listByGoal(goal).lastOrNull()?.endedAt != null && !chat.screen.value.isSending
        }
        assertEquals(expected, storage.goals.resolve(goal).state)
        val summary = chat.goalSummaries().single { it.id == goal }
        assertTrue(!summary.status.modelSummary.isNullOrBlank())
        assertEquals(expected == "PAUSED", summary.canContinue)
        if (expected == "BLOCKED") {
            assertTrue(chat.recheckGoalBlocker(goal))
            assertEquals("PAUSED", storage.goals.resolve(goal).state)
        }
        val turns = storage.turns.listBySession(session)
        assertEquals(1, turns.size)
        assertTrue(storage.toolCalls.listByTurn(turns.single().id).any { it.name == "goal.report" && it.state == "COMPLETED" })
    } finally {
        chat.backgroundTasks.value.filter { it.sessionId == session && it.running }.forEach { chat.stopTask(it.id) }
        compose.waitUntil(10000) { chat.backgroundTasks.value.none { it.sessionId == session && it.running } }
        chat.closeSession()
        chat.setMode(previous.mode)
        chat.setTurnBudgets(previous.budgets)
        container.providerService.delete(provider)
    }
}
''')
