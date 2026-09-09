package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.HelixApplication
import com.helix.app.goal.GoalCriterionEditor
import com.helix.app.goal.GoalCriterionVerifier
import com.helix.app.goal.GoalEvidenceReview
import com.helix.app.goal.GoalToolArtifactStore
import com.helix.app.goal.GoalToolEvidenceReader
import com.helix.app.goal.toRuntimeGoal
import com.helix.app.goal.toStoredGoal
import com.helix.core.model.CriterionVerificationBinding
import com.helix.core.model.CriterionVerificationMethod
import com.helix.core.model.GoalBudgets
import com.helix.core.model.GoalState
import com.helix.core.model.SystemClock
import com.helix.core.model.TurnState
import com.helix.core.storage.content.FileContentStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GoalEvidenceContinueUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun providerFreeContinueCompletesAndKeepsGoalStatusVisible() = exerciseReview(false)

    @Test
    fun writtenSnapshotReviewCompletesWithTheSelectedArtifact() = exerciseReview(true)

    private fun exerciseReview(written: Boolean) {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val container = app.appContainer
        val service = container.chatService
        val storage = container.storage
        val session = "goal-review-ui-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        storage.sessions.create(session, "Goal review UI", null, null, now)
        service.openSession(session)
        val goalId = createReviewGoal(service, session)
        val ui = ReviewUiState()
        try {
            seedReview(goalId, session, written)
            val criterionId =
                storage.goals
                    .resolve(goalId)
                    .criteria
                    .single()
                    .id
            runBlocking { service.clearGoalEvidenceReview(goalId, criterionId) }
            compose.waitUntil(10000) { service.screen.value.openSessionId == session }
            renderReview(service, goalId, criterionId, ui)
            reviewSource(goalId, written)
            assertEquals("PAUSED", storage.goals.resolve(goalId).state)
            compose
                .onNodeWithTag("goal-continue-$goalId")
                .performScrollTo()
                .assertIsEnabled()
                .performClick()
            compose.waitUntil(10000) { storage.goals.resolve(goalId).state == "COMPLETED" }
            compose.waitUntil(
                10000,
            ) { compose.onAllNodesWithText(ui.completedLabel).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(ui.completedLabel).performScrollTo().assertIsDisplayed()
            compose.runOnIdle {
                assertFalse(ui.dismissed)
                assertFalse(ui.sent)
            }
            assertEquals(1, storage.turns.listBySession(session).size)
            val evidence =
                storage.goals
                    .resolve(goalId)
                    .criteria
                    .single()
                    .evidence
            assertEquals(written, evidence?.artifactRef != null)
        } catch (failure: Throwable) {
            diagnostic(goalId, ui.dismissed, ui.sent)
            throw failure
        } finally {
            service.closeSession()
            service.dismissBlocked()
            storage.goals.delete(goalId)
            storage.sessions.archive(session, System.currentTimeMillis())
        }
    }

    private fun createReviewGoal(
        service: com.helix.app.chat.ChatService,
        session: String,
    ): String =
        runBlocking {
            service.createGoal("Review UI $session", listOf("Check result"), GoalBudgets(5, 10, 10000, 60000, 60000, 0))
        }

    private fun renderReview(
        service: com.helix.app.chat.ChatService,
        goalId: String,
        criterionId: String,
        ui: ReviewUiState,
    ) {
        compose.setContent {
            ui.completedLabel =
                androidx.compose.ui.res
                    .stringResource(com.helix.app.R.string.goal_state_completed)
            MaterialTheme {
                if (ui.reviewing.value) {
                    GoalEvidenceDialog(service, goalId, criterionId, true) { ui.reviewing.value = false }
                } else {
                    GoalDialog(service, "", { ui.dismissed = true }, { ui.sent = true }, selectedGoalId = goalId)
                }
            }
        }
    }

    private fun reviewSource(
        goalId: String,
        written: Boolean,
    ) {
        val sourceTag = "evidence-source-$goalId${if (written) "-written" else ""}"
        compose.waitUntil(10000) {
            compose.onAllNodesWithTag(sourceTag).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(sourceTag).performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("evidence-confirm").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("evidence-content").performScrollToNode(hasText("Verified fixture"))
        compose.onNodeWithText("Verified fixture").assertIsDisplayed()
        compose.onNodeWithTag("evidence-confirm").performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithTag("goal-continue-$goalId").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun diagnostic(
        goalId: String,
        dismissed: Boolean,
        sent: Boolean,
    ) {
        val container = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer
        val storage = container.storage
        val service = container.chatService
        val snapshot = storage.goals.resolve(goalId)
        val info =
            android.os.Bundle().apply {
                putString(
                    "goalPreflightDiagnostic",
                    "state=${snapshot.state},pending=${snapshot.criteria.single().pendingReview != null}," +
                        "dismissed=$dismissed,sent=$sent,blocked=${service.screen.value.blockedReason}," +
                        "runs=${storage.goalRuns.listByGoal(goalId).map { it.outcome }}",
                )
            }
        androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation()
            .sendStatus(0, info)
    }

    private fun seedResult(
        storage: com.helix.core.storage.HelixStorage,
        goalId: String,
        written: Boolean,
    ) {
        val args =
            buildJsonObject {
                if (written) {
                    put("path", JsonPrimitive("scope:ui:output/review.txt"))
                    put("content", JsonPrimitive("Verified fixture"))
                }
            }
        val output =
            if (written) {
                buildJsonObject {
                    put("path", JsonPrimitive("scope:ui:output/review.txt"))
                    put("sizeBytes", JsonPrimitive("Verified fixture".toByteArray().size))
                    put(
                        "sha256",
                        JsonPrimitive(FileContentStore.sha256Hex("Verified fixture".toByteArray())),
                    )
                }.toString()
            } else {
                "Verified fixture"
            }
        val name = if (written) "write" else "time.now"
        storage.toolCalls.append(goalId, goalId, "call", name, "1", args.toString(), "COMPLETED")
        val result = storage.toolResults.append(goalId, goalId, "SUCCEEDED", "Fixture result", output)
        storage.toolResults.markVerified(result)
    }

    private fun seedReview(
        goalId: String,
        session: String,
        written: Boolean,
    ) {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val container = app.appContainer
        val storage = container.storage
        val clock = SystemClock()
        val now = clock.now().toEpochMilli()
        val goal = storage.goals.resolve(goalId).toRuntimeGoal()
        val criterion =
            goal.criteria.single().withBinding(
                "Check result",
                CriterionVerificationBinding(CriterionVerificationMethod.MANUAL_REVIEW, ""),
            )
        val running = goal.copy(state = GoalState.RUNNING, runCount = 1, criteria = listOf(criterion))
        storage.goals.updateGoal(running.toStoredGoal())
        storage.goalRuns.open(goalId, goalId, "USER_OPEN", now)
        var turn = storage.turns.start(goalId, session, now)
        storage.goalTurnBindings.bind(goalId, goalId)
        listOf(
            TurnState.BUILDING_CONTEXT,
            TurnState.WAITING_MODEL,
            TurnState.RECEIVING_MODEL,
            TurnState.COMPLETED,
        ).forEach {
            turn = storage.turns.updateState(turn, it, 0, if (it == TurnState.COMPLETED) now else null, null)
        }
        seedResult(storage, goalId, written)
        storage.goalRuns.finish(storage.goalRuns.resolve(goalId), "RUN_FINISHED", now, 0, 0, 0, 0)
        storage.goals.updateGoal(storage.goals.resolve(goalId).copy(state = "PAUSED"))
        val reader = GoalToolEvidenceReader(storage, container.toolPipeline.registry)
        val artifacts = GoalToolArtifactStore(storage, File(app.filesDir, "workspaces/app"), reader)
        val verifier = GoalCriterionVerifier(reader, artifacts, clock)
        val review =
            GoalEvidenceReview(
                goalId,
                requireNotNull(criterion.binding).hash(criterion.id, criterion.description),
                reader.read(goalId, goalId).hash,
            )
        val editor = GoalCriterionEditor(storage, verifier, clock) { UUID.randomUUID().toString() }
        editor.stageReview(goalId, criterion.id, review)
    }
}

private class ReviewUiState {
    var dismissed = false
    var sent = false
    var completedLabel = ""
    val reviewing = mutableStateOf(true)
}
