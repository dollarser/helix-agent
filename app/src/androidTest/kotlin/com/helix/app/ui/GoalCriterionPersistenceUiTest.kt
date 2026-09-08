package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.HelixApplication
import com.helix.core.model.CriterionVerificationMethod
import com.helix.core.model.GoalBudgets
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GoalCriterionPersistenceUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun goalManagementSavesBindingWithoutStartingRun() {
        val container = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer
        val service = container.chatService
        val storage = container.storage
        val session = "binding-ui-${UUID.randomUUID()}"
        storage.sessions.create(session, "Binding UI", null, null, System.currentTimeMillis())
        service.openSession(session)
        val goalId =
            runBlocking {
                service.createGoal(
                    "Check clock",
                    listOf("Local time succeeds"),
                    GoalBudgets(5, 10, 10000, 60000, 60000, 0),
                )
            }
        try {
            val before = storage.goals.resolve(goalId)
            val criterionId = before.criteria.single().id
            renderGoal(service, goalId)
            compose.waitUntil(10000) { service.screen.value.openSessionId == session }
            enterToolRule(goalId, criterionId)
            assertNull(
                storage.goals
                    .resolve(goalId)
                    .criteria
                    .single()
                    .binding,
            )
            compose.onNodeWithTag("criterion-save").performClick()
            compose.waitUntil(10000) {
                storage.goals
                    .resolve(goalId)
                    .criteria
                    .single()
                    .binding != null
            }
            val after = storage.goals.resolve(goalId)
            val binding = after.criteria.single().binding
            assertEquals(CriterionVerificationMethod.LOCAL_TOOL_SUCCESS, binding?.method)
            assertEquals("time.now", binding?.argument)
            assertEquals(before.state, after.state)
            assertEquals(0, after.runCount)
            assertEquals(0, storage.goalRuns.listByGoal(goalId).size)
        } finally {
            service.closeSession()
            storage.goals.delete(goalId)
            storage.sessions.archive(session, System.currentTimeMillis())
        }
    }

    private fun enterToolRule(
        goalId: String,
        criterionId: String,
    ) {
        compose.onNodeWithTag("goal-criteria-$goalId").performScrollTo().performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithTag("goal-bind-$criterionId").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("goal-bind-$criterionId").performScrollTo().performClick()
        compose.onNodeWithTag("criterion-method-LOCAL_TOOL_SUCCESS").performScrollTo().performClick()
        compose.onNodeWithTag("criterion-argument").performScrollTo().performTextInput("time.now")
    }

    private fun renderGoal(
        service: com.helix.app.chat.ChatService,
        goalId: String,
    ) {
        compose.setContent { MaterialTheme { GoalDialog(service, "", {}, {}, selectedGoalId = goalId) } }
    }
}
