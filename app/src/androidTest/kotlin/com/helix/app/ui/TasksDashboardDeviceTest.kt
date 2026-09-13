package com.helix.app.ui

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.helix.app.MainActivity
import com.helix.core.model.PlanArtifact
import com.helix.core.model.PlanId
import com.helix.core.model.PlanStep
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/**
 * The P0-B Tasks dashboard (doc section 13): the drawer gains the tasks and capabilities
 * destinations, a review-required (READY) plan surfaces in the "needs you" bucket, and the
 * review dialog's cancel decision drives the plan to REJECTED so it leaves the queue.
 */
class TasksDashboardDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun tasksDestinationListsReadyPlanAndCancelLeavesTheQueue() =
        runBlocking {
            compose.resetDeterministicUiState()
            val container = compose.container()
            val planId = "plan-device-${System.currentTimeMillis()}"
            container.storage.withTransaction {
                container.storage.plans.save(
                    PlanArtifact(
                        id = PlanId(planId),
                        objective = "Device test plan",
                        assumptions = emptyList(),
                        steps = listOf(PlanStep("step one", "do the thing")),
                        acceptanceCriteria = listOf("the thing is done"),
                        risks = emptyList(),
                        version = 1,
                    ),
                    "READY",
                    null,
                )
            }

            // The drawer now offers the two new destinations.
            compose.onNodeWithTag("open-navigation").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("navigation-tasks").assertExists()
            compose.onNodeWithTag("navigation-capabilities").assertExists()

            compose.navigateTo("tasks")
            compose.onNodeWithTag("screen-tasks").assertExists()
            compose.onNodeWithTag("tasks-bucket-needs_you").assertExists()
            compose.onNodeWithTag("tasks-plan-$planId").assertExists()

            compose.onNodeWithTag("tasks-plan-review-$planId").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("plan-review-$planId").assertExists()
            compose.onNodeWithTag("plan-execute-$planId").assertExists()
            compose.onNodeWithTag("plan-revise-$planId").assertExists()
            compose.onNodeWithTag("plan-cancel-$planId").performClick()
            compose.waitForIdle()

            compose.onNodeWithTag("plan-review-$planId").assertDoesNotExist()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("tasks-plan-$planId").fetchSemanticsNodes().isEmpty()
            }
        }
}
