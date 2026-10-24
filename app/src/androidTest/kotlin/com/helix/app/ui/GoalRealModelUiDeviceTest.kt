package com.helix.app.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.MainActivity
import com.helix.app.application.agent.GoalRoundAllowance
import com.helix.app.provider.ProviderDraft
import com.helix.core.agent.TurnBudgetBounds
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.TurnState
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Opt-in real-model Goal UI acceptance; provider/session setup is a fixture, Goal actions are UI clicks. */
@RunWith(AndroidJUnit4::class)
class GoalRealModelUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val container get() = compose.container()
    private val storage get() = container.storage
    private val service get() = container.chatService
    private var providerId: String? = null
    private var sessionId: String? = null
    private var goalId: String? = null

    @Test
    fun creationBlockerRecheckAndExplicitContinueUseTheRealModelThroughUi() =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue("requires an explicitly configured real endpoint", args.getString("helix.goalUi") == "true")
            val previous = service.runControl.value
            try {
                compose.resetDeterministicUiState()
                val model = requireNotNull(args.getString("helix.goalUi.model")).also { require(it.isNotBlank()) }
                createProvider(model, requireNotNull(args.getString("helix.goalUi.port")).toInt())
                sessionId = service.createSession("Goal real UI fixture", requireNotNull(providerId), model)
                service.openSession(requireNotNull(sessionId))
                service.setTurnBudgets(TurnBudgetBounds.DEFAULT)
                service.setReasoning(ReasoningEffort.OFF)
                compose.navigateTo("sessions")
                compose.waitUntil(10_000) { service.screen.value.openSessionId == sessionId }
                compose.onNodeWithTag("chat-input").performTextInput("/goal")
                compose.onNodeWithTag("chat-send").performClick()
                createGoalThroughUi()
                continueAndVerify(1, "GOAL_UI_FIRST", "BLOCKED", start = false)
                compose.onNodeWithTag("chat-composer-options").performClick()
                compose.onNodeWithTag("goal-manage").performClick()
                compose.onNodeWithTag("goal-continue-$goalId").performScrollTo().assertIsNotEnabled()
                compose.onNodeWithTag("goal-settings").performClick()
                compose.onNodeWithTag("goal-edit-budgets-$goalId").performScrollTo().performClick()
                replace("goal-budget-0", "2")
                compose.onNodeWithTag("goal-save").performClick()
                compose.waitUntil(10_000) {
                    storage.goals
                        .resolve(requireNotNull(goalId))
                        .budgets.maxGoalRounds == 2
                }
                assertRunCount(1)
                compose.navigateTo("sessions")
                compose.onNodeWithTag("chat-input").performTextInput(reportObjective("complete", "GOAL_UI_SECOND"))
                compose.onNodeWithTag("chat-composer-options").performClick()
                compose.onNodeWithTag("goal-manage").performClick()
                recheckAndEditObjective()
                continueAndVerify(2, "GOAL_UI_SECOND", "COMPLETED")
                saveEvidence(model)
            } finally {
                service.stop()
                compose.waitUntil(10_000) { !service.screen.value.isSending }
                service.closeSession()
                container.runControlStore.setGoalBudgets(previous.goalBudgets)
                service.setMode(previous.mode)
                service.setTurnBudgets(previous.budgets)
                service.setReasoning(previous.reasoning)
                goalId?.let(storage.goals::delete)
                sessionId?.let { storage.sessions.archive(it, System.currentTimeMillis()) }
                providerId?.let { container.providerService.delete(it) }
            }
        }

    private suspend fun createProvider(
        model: String,
        port: Int,
    ) {
        require(port in 1..65535)
        val draft =
            ProviderDraft(
                null,
                "Goal UI ${UUID.randomUUID()}",
                ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                NormalizedEndpoint.parse("http://10.0.2.2:$port/v1"),
                model,
                "{}",
                false,
            )
        providerId = container.providerService.create(draft, null)
        check(container.providerService.runConnectionTest(requireNotNull(providerId)) is ProbeOutcome.Ok) {
            "The configured real-model provider probe failed"
        }
    }

    private fun createGoalThroughUi() {
        val objective = reportObjective("blocked", "GOAL_UI_FIRST") + " Fixture ${UUID.randomUUID()}."
        compose.navigateTo("settings/defaults")
        compose.onNodeWithTag("goal-defaults-edit").performScrollTo().performClick()
        replace("goal-budget-0", "1")
        compose.onNodeWithTag("goal-save").performClick()
        compose.navigateTo("sessions")
        compose.onNodeWithTag("chat-input").performTextInput(objective)
        compose.onNodeWithTag("chat-send").performClick()
        compose.waitUntil(10_000) {
            goalId =
                storage.goals
                    .list()
                    .singleOrNull { it.objective == objective }
                    ?.id
            goalId != null
        }
    }

    private fun reportObjective(
        status: String,
        marker: String,
    ): String =
        "Read goal.get and call goal.update with its id and expected_revision, status=$status and summary=$marker. " +
            "Then reply only $marker. Do not use any other tools. " +
            if (status == "blocked") "The next step requires new input from the user; do not report completion." else ""

    private fun recheckAndEditObjective() {
        compose.onNodeWithTag("goal-continue-$goalId").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("goal-recheck-$goalId").performScrollTo().performClick()
        compose.waitUntil(10_000) { storage.goals.resolve(requireNotNull(goalId)).state == "PAUSED" }
        assertRunCount(1)
        val objective = reportObjective("complete", "GOAL_UI_SECOND")
        compose.onNodeWithTag("goal-objective-edit-$goalId").performScrollTo().performClick()
        replace("goal-objective-$goalId", objective)
        compose.onNodeWithTag("goal-objective-save-$goalId").performScrollTo().performClick()
        compose.waitUntil(10_000) { storage.goals.resolve(requireNotNull(goalId)).objective == objective }
        assertRunCount(1)
    }

    private fun replace(
        tag: String,
        text: String,
    ) {
        compose.onNodeWithTag(tag).performScrollTo().performTextClearance()
        compose.onNodeWithTag(tag).performTextInput(text)
    }

    private fun continueAndVerify(
        count: Int,
        marker: String,
        expectedState: String,
        start: Boolean = true,
    ) {
        if (start) {
            compose
                .onNodeWithTag("goal-continue-$goalId")
                .performScrollTo()
                .assertIsEnabled()
                .performClick()
        }
        compose.waitUntil(180_000) {
            val turns = storage.turns.listBySession(requireNotNull(sessionId))
            turns.size == count && turns.all { TurnState.valueOf(it.state).isTerminal } &&
                !service.screen.value.isSending && storage.goals.resolve(requireNotNull(goalId)).state == expectedState
        }
        val goal = storage.goals.resolve(requireNotNull(goalId))
        assertEquals(expectedState, goal.state)
        assertEquals(0, GoalRoundAllowance(storage).used(goal.id))
        assertTrue(goal.totalTokens > 0)
        val turn = storage.turns.listBySession(requireNotNull(sessionId)).last()
        assertEquals(turn.errorCode, "COMPLETED", turn.state)
        val calls = storage.toolCalls.listByTurn(turn.id)
        assertTrue(calls.any { it.name in setOf("goal.update", "goal.report") && it.state == "COMPLETED" })
        assertTrue(calls.all { it.name in setOf("goal.get", "goal.update", "goal.report") })
        val binding = requireNotNull(storage.goalTurnBindings.byTurn(turn.id))
        val run = storage.goalRuns.resolve(binding.runId)
        assertEquals(goalId, run.goalId)
        assertEquals(if (expectedState == "BLOCKED") "BLOCKED(MODEL_REPORTED)" else "MODEL_COMPLETED", run.outcome)
        val text =
            storage.messages
                .listBySession(requireNotNull(sessionId))
                .filter { it.turnId == turn.id && it.role == "ASSISTANT" && it.kind == "TEXT" }
                .mapNotNull(storage.messages::readContent)
                .joinToString("\n")
        assertTrue("Real model output must contain the requested marker", text.contains(marker))
        assertLatestReplyFullyVisible(marker)
        assertRunCount(count)
    }

    private fun assertLatestReplyFullyVisible(marker: String) {
        compose.waitForIdle()
        val viewport = compose.onNodeWithTag("chat-timeline").getUnclippedBoundsInRoot()
        val reply =
            compose.onNode(hasTestTag("chat-message-assistant") and hasAnyDescendant(hasText(marker, substring = true)))
        reply.assertIsDisplayed()
        val bounds = reply.getUnclippedBoundsInRoot()
        assertTrue("Latest short reply must be fully visible without test scrolling", bounds.top >= viewport.top)
        assertTrue("Latest reply must not be clipped below the viewport", bounds.bottom <= viewport.bottom)
        val end = compose.onNodeWithTag("chat-timeline-end")
        end.assertIsDisplayed()
        assertTrue(end.getUnclippedBoundsInRoot().bottom <= viewport.bottom)
        compose.onNodeWithTag("chat-scroll-latest").assertDoesNotExist()
    }

    private fun assertRunCount(count: Int) {
        assertEquals(count, storage.goalRuns.listByGoal(requireNotNull(goalId)).size)
        assertEquals(count, storage.turns.listBySession(requireNotNull(sessionId)).size)
        val modelCalls =
            storage.turns.listBySession(requireNotNull(sessionId)).sumOf {
                storage.modelCalls
                    .listByTurn(it.id)
                    .size
            }
        assertEquals(modelCalls, storage.goals.resolve(requireNotNull(goalId)).modelCalls)
    }

    private fun saveEvidence(model: String) {
        val goal = storage.goals.resolve(requireNotNull(goalId))
        val result =
            buildJsonObject {
                put("passed", true)
                put("model", model)
                put("goalState", goal.state)
                put("modelCalls", goal.modelCalls)
                put("automaticRounds", GoalRoundAllowance(storage).used(goal.id))
                put("tokens", goal.totalTokens)
                put("runs", storage.goalRuns.listByGoal(goal.id).size)
                put(
                    "scope",
                    "UI create, model blocker, allowance edit without execution, " +
                        "explicit recheck/Continue, model completion",
                )
                put("latestRepliesFullyVisibleWithoutTestScroll", true)
                put("completionClaim", true)
            }
        val directory = File(compose.activity.filesDir, "hxa102-goal-ui").apply { mkdirs() }
        captureUi("conversation.png")
        compose.onNodeWithTag("chat-composer-options").performClick()
        compose.onNodeWithTag("goal-manage").performClick()
        compose.onNodeWithTag("goal-continue-$goalId").performScrollTo().assertIsNotEnabled()
        captureUi("completed.png")
        compose.onNodeWithTag("goal-close").performClick()
        directory.resolve("result.json").writeText(result.toString())
    }

    private fun captureUi(name: String) {
        compose.waitForIdle()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            File(compose.activity.filesDir, "hxa102-goal-ui/$name").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }
}
