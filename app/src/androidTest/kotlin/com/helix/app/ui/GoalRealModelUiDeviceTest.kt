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
import com.helix.app.provider.ProviderDraft
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import com.helix.provider.api.CleartextAuthorization
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
    fun creationBudgetPauseAndExplicitContinueUseTheRealModelThroughUi() =
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
                service.setTurnBudgets(TurnBudgets(4, 1, 32768, 2048, 34816))
                compose.navigateTo("sessions")
                compose.waitUntil(10_000) { service.screen.value.openSessionId == sessionId }
                compose.onNodeWithTag("chat-mode-menu").performClick()
                compose.onNodeWithTag("chat-mode-goal").performClick()
                createGoalThroughUi()
                assertRunCount(0)
                continueAndVerify(1, "GOAL_UI_FIRST")
                compose.onNodeWithTag("chat-input").performTextInput("Reply only GOAL_UI_SECOND. Do not call tools.")
                compose.onNodeWithTag("goal-manage").performClick()
                compose.onNodeWithTag("goal-continue-$goalId").performScrollTo().assertIsNotEnabled()
                compose.onNodeWithTag("goal-edit-budgets-$goalId").performScrollTo().performClick()
                replace("goal-budget-0", "2")
                compose.onNodeWithTag("goal-save").performClick()
                compose.waitUntil(10_000) {
                    storage.goals
                        .resolve(requireNotNull(goalId))
                        .budgets.maxModelCalls == 2
                }
                assertRunCount(1)
                continueAndVerify(2, "GOAL_UI_SECOND")
                saveEvidence(model)
            } finally {
                service.stop()
                compose.waitUntil(10_000) { !service.screen.value.isSending }
                service.closeSession()
                service.setMode(previous.mode)
                service.setTurnBudgets(previous.budgets)
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
                CleartextAuthorization("10.0.2.2", port),
                emptyList(),
            )
        providerId = container.providerService.create(draft, null, cleartextConfirmed = true)
        check(container.providerService.runConnectionTest(requireNotNull(providerId)) is ProbeOutcome.Ok) {
            "The configured real-model provider probe failed"
        }
    }

    private fun createGoalThroughUi() {
        val objective = "Reply only GOAL_UI_FIRST. Do not call tools. Fixture ${UUID.randomUUID()}."
        compose.onNodeWithTag("chat-input").performTextInput(objective)
        compose.onNodeWithTag("goal-manage").performClick()
        compose.onNodeWithTag("goal-create").performClick()
        compose.onNodeWithTag("goal-criteria").performTextInput("The output has independently verified evidence.")
        replace("goal-budget-0", "1")
        compose.onNodeWithTag("goal-save").performClick()
        compose.waitUntil(10_000) {
            goalId =
                storage.goals
                    .list()
                    .singleOrNull { it.objective == objective }
                    ?.id
            goalId != null
        }
        assertEquals("READY", storage.goals.resolve(requireNotNull(goalId)).state)
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
    ) {
        compose
            .onNodeWithTag("goal-continue-$goalId")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        compose.waitUntil(180_000) {
            val turns = storage.turns.listBySession(requireNotNull(sessionId))
            turns.size == count && turns.all { TurnState.valueOf(it.state).isTerminal }
        }
        val goal = storage.goals.resolve(requireNotNull(goalId))
        assertEquals("PAUSED", goal.state)
        assertEquals(count, goal.modelCalls)
        assertTrue(goal.totalTokens > 0)
        val turn = storage.turns.listBySession(requireNotNull(sessionId)).last()
        assertTrue(storage.toolCalls.listByTurn(turn.id).isEmpty())
        val binding = requireNotNull(storage.goalTurnBindings.byTurn(turn.id))
        val run = storage.goalRuns.resolve(binding.runId)
        assertEquals(goalId, run.goalId)
        assertEquals("BUDGET_EXHAUSTED(maxModelCalls)", run.outcome)
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
        assertEquals(count, storage.goals.resolve(requireNotNull(goalId)).modelCalls)
    }

    private fun saveEvidence(model: String) {
        val goal = storage.goals.resolve(requireNotNull(goalId))
        val result =
            buildJsonObject {
                put("passed", true)
                put("model", model)
                put("goalState", goal.state)
                put("modelCalls", goal.modelCalls)
                put("tokens", goal.totalTokens)
                put("runs", storage.goalRuns.listByGoal(goal.id).size)
                put("scope", "UI create, explicit Continue, budget pause, UI budget extension, explicit Continue")
                put("latestRepliesFullyVisibleWithoutTestScroll", true)
                put("completionClaim", false)
            }
        val directory = File(compose.activity.filesDir, "hxa102-goal-ui").apply { mkdirs() }
        captureUi("conversation.png")
        compose.onNodeWithTag("goal-manage").performClick()
        compose.onNodeWithTag("goal-continue-$goalId").performScrollTo().assertIsNotEnabled()
        captureUi("budget-paused.png")
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
