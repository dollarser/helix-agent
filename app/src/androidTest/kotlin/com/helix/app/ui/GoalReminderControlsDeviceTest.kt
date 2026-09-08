package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import com.helix.app.HelixApplication
import com.helix.app.chat.GoalRunCoordinator
import com.helix.app.chat.GoalTurnStart
import com.helix.app.chat.ModelStreamTerminal
import com.helix.app.chat.TurnStartSpec
import com.helix.app.goal.GoalReminderPayload
import com.helix.core.agent.GoalWakeReason
import com.helix.core.model.GoalBudgets
import com.helix.core.model.SystemClock
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GoalReminderControlsDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun dialogReminderSurvivesReopenAndClearCancelsWorkWithoutContinuing() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val container = app.appContainer
        val service = container.chatService
        val sessionId = "reminder-ui-${UUID.randomUUID()}"
        val id = createPausedGoal(app, sessionId)
        val before = container.storage.goals.resolve(id)
        val runs = container.storage.goalRuns.listByGoal(id)
        var open by mutableStateOf(true)
        try {
            service.openSession(sessionId)
            compose.waitUntil(10_000) { service.screen.value.openSessionId == sessionId }
            compose.setContent {
                MaterialTheme {
                    if (open) {
                        GoalDialog(
                            service,
                            "",
                            { open = false },
                            { error("Unexpected Continue") },
                            selectedGoalId = id,
                        )
                    }
                }
            }
            verifyReminder(app, id) {
                compose.onNodeWithTag("goal-close").performClick()
                compose.runOnIdle { open = true }
            }
            assertEquals(before, container.storage.goals.resolve(id))
            assertEquals(runs, container.storage.goalRuns.listByGoal(id))
            assertEquals(
                1,
                container.storage.turns
                    .listBySession(sessionId)
                    .size,
            )
        } finally {
            compose.runOnIdle { open = false }
            service.closeSession()
            container.privacyDeletionService.deleteGoal(id)
            container.storage.sessions.archive(sessionId, System.currentTimeMillis())
        }
    }

    @Test
    fun budgetEditorDiscardsUnsavedChangesAndPersistsAllLimitsWithoutContinue() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val container = app.appContainer
        val service = container.chatService
        val sessionId = "budget-ui-${UUID.randomUUID()}"
        val id = createPausedGoal(app, sessionId)
        val before = container.storage.goals.resolve(id)
        val runs = container.storage.goalRuns.listByGoal(id)
        var open by mutableStateOf(true)
        try {
            service.openSession(sessionId)
            compose.waitUntil(10_000) { service.screen.value.openSessionId == sessionId }
            compose.setContent {
                MaterialTheme {
                    if (open) {
                        GoalDialog(
                            service,
                            "",
                            { open = false },
                            { error("Unexpected Continue") },
                            selectedGoalId = id,
                        )
                    }
                }
            }
            openBudgetEditor(id)
            compose.onNodeWithTag("goal-budget-0").performScrollTo().performTextReplacement("-1")
            compose.onNodeWithTag("goal-save").assertIsNotEnabled()
            compose.onNodeWithTag("goal-editor-close").performClick()
            assertEquals(before, container.storage.goals.resolve(id))
            openBudgetEditor(id)
            listOf("5", "9", "2000", "120", "20", "1").forEachIndexed { index, value ->
                compose.onNodeWithTag("goal-budget-$index").performScrollTo().performTextReplacement(value)
            }
            val expected = GoalBudgets(5, 9, 2000, 120000, 20000, 1)
            compose.onNodeWithTag("goal-save").performClick()
            compose.waitUntil(10_000) {
                container.storage.goals
                    .resolve(id)
                    .budgets == expected
            }
            assertEquals(before.copy(budgets = expected), container.storage.goals.resolve(id))
            assertEquals(runs, container.storage.goalRuns.listByGoal(id))
            assertEquals(
                1,
                container.storage.turns
                    .listBySession(sessionId)
                    .size,
            )
        } finally {
            compose.runOnIdle { open = false }
            service.closeSession()
            container.privacyDeletionService.deleteGoal(id)
            container.storage.sessions.archive(sessionId, System.currentTimeMillis())
        }
    }

    @Test
    fun deletionRequiresConfirmationAndRetriesReminderFailureWithoutLosingGoal() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val container = app.appContainer
        val sessionId = "delete-ui-${UUID.randomUUID()}"
        val id = createPausedGoal(app, sessionId)
        val before = container.storage.goals.resolve(id)
        var failCleanup = true
        var open by mutableStateOf(true)
        try {
            container.chatService.openSession(sessionId)
            compose.waitUntil(10_000) { container.chatService.screen.value.openSessionId == sessionId }
            compose.setContent {
                MaterialTheme {
                    if (open) {
                        GoalDialog(
                            container.chatService,
                            "",
                            { open = false },
                            { error("Unexpected Continue") },
                            selectedGoalId = id,
                            onDeleteGoal = { goalId ->
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    if (failCleanup) {
                                        com.helix.app.goal
                                            .GoalDeletionCoordinator(container.storage) {
                                                error("Fixture reminder cancellation failure")
                                            }.delete(goalId)
                                    } else {
                                        container.privacyDeletionService.deleteGoal(goalId)
                                    }
                                }
                            },
                        )
                    }
                }
            }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("goal-delete-$id").fetchSemanticsNodes().size == 1
            }
            verifyDeletionConfirmation(app, id) { failCleanup = false }
            assertEquals(
                1,
                container.storage.turns
                    .listBySession(sessionId)
                    .size,
            )
            assertTrue(
                container.storage.goalRuns
                    .listByGoal(id)
                    .isEmpty(),
            )
            assertEquals("PAUSED", before.state)
        } finally {
            compose.runOnIdle { open = false }
            container.chatService.closeSession()
            if (container.storage.goals.find(id) != null) container.privacyDeletionService.deleteGoal(id)
            container.storage.sessions.archive(sessionId, System.currentTimeMillis())
        }
    }

    private fun verifyDeletionConfirmation(
        app: HelixApplication,
        id: String,
        allowCleanup: () -> Unit,
    ) {
        val storage = app.appContainer.storage
        val original = storage.goals.resolve(id)
        compose.onNodeWithTag("goal-remind-$id").performScrollTo().performClick()
        awaitClearEnabled(id)
        val scheduled = work(app, id).single()
        compose.onNodeWithTag("goal-delete-$id").performScrollTo().performClick()
        compose.onNodeWithTag("goal-delete-cancel").performClick()
        assertEquals(scheduled.id, work(app, id).single().id)
        assertTrue(!work(app, id).single().state.isFinished)
        compose.onNodeWithTag("goal-delete-$id").performScrollTo().performClick()
        compose.onNodeWithTag("goal-delete-confirm").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("goal-delete-failed").fetchSemanticsNodes().size == 1
        }
        assertEquals(
            original.copy(nextCheckpoint = storage.goals.resolve(id).nextCheckpoint),
            storage.goals.resolve(id),
        )
        assertTrue(!work(app, id).single().state.isFinished)
        compose.runOnIdle { allowCleanup() }
        compose.onNodeWithTag("goal-delete-confirm").performClick()
        compose.waitUntil(10_000) { storage.goals.find(id) == null }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("goal-delete-$id").fetchSemanticsNodes().isEmpty()
        }
        assertTrue(work(app, id).all { it.state.isFinished })
    }

    private fun openBudgetEditor(id: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("goal-edit-budgets-$id").fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag("goal-edit-budgets-$id").performScrollTo().performClick()
    }

    private fun verifyReminder(
        app: HelixApplication,
        id: String,
        reopen: () -> Unit,
    ) {
        val storage = app.appContainer.storage
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("goal-remind-$id").fetchSemanticsNodes().size == 1
        }
        val now = System.currentTimeMillis()
        compose.onNodeWithTag("goal-remind-$id").performScrollTo().performClick()
        awaitClearEnabled(id)
        val checkpoint = requireNotNull(storage.goals.resolve(id).nextCheckpoint)
        assertTrue(checkpoint in (now + 1_800_000)..(System.currentTimeMillis() + 1_800_000))
        val scheduled = work(app, id).single()
        assertTrue(!scheduled.state.isFinished)
        reopen()
        awaitClearEnabled(id)
        assertEquals(checkpoint, storage.goals.resolve(id).nextCheckpoint)
        assertEquals(scheduled.id, work(app, id).single().id)
        compose.onNodeWithTag("goal-reminder-clear-$id").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            storage.goals.resolve(id).nextCheckpoint == null && work(app, id).all { it.state.isFinished }
        }
    }

    private fun work(
        app: HelixApplication,
        id: String,
    ) = WorkManager.getInstance(app).getWorkInfosForUniqueWork(GoalReminderPayload.uniqueWorkName(id)).get()

    private fun awaitClearEnabled(id: String) {
        compose.waitUntil(10_000) {
            val node = compose.onAllNodesWithTag("goal-reminder-clear-$id").fetchSemanticsNodes().singleOrNull()
            node?.config?.contains(SemanticsProperties.Disabled) == false
        }
    }

    private fun createPausedGoal(
        app: HelixApplication,
        sessionId: String,
    ): String {
        val storage = app.appContainer.storage
        val clock = SystemClock()
        storage.sessions.create(sessionId, "Reminder UI fixture", null, null, clock.now().toEpochMilli())
        val coordinator = GoalRunCoordinator(storage, clock) { UUID.randomUUID().toString() }
        val id =
            coordinator.create(
                "Reminder UI fixture",
                listOf("Verified result"),
                GoalBudgets(2, 4, 1000, 60000, 10000, 0),
            )
        val started =
            requireNotNull(
                coordinator.start(
                    GoalTurnStart(
                        id,
                        GoalWakeReason.USER_OPEN,
                        TurnStartSpec(sessionId, "turn-$sessionId", "model-$sessionId", "snapshot", "run"),
                        TurnBudgets(2, 4, 500, 500, 10000),
                    ),
                ),
            )
        started.coordinator.beginModelStream()
        started.coordinator.terminalize(ModelStreamTerminal(TurnState.COMPLETED, null))
        return id
    }
}
