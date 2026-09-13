package com.helix.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.helix.app.MainActivity
import com.helix.core.model.TurnState
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/**
 * The P0-B Artifact Center (doc section 28 / PX-02 "结果可交付"): the drawer's artifacts
 * destination lists every finished task's deliverable result — a seeded COMPLETED turn appears
 * with its session title and a "已完成" status, and its result view loads the persisted summary
 * and enables the honest actions (Share is enabled only once the result is present; Collect is
 * offered for an uncollected finished turn). Dismissing the view returns cleanly to the list.
 */
class ArtifactCenterDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun artifactsPageListsCompletedResultAndExposesActions() {
        runBlocking {
            compose.resetDeterministicUiState()
            val container = compose.container()
            val storage = container.storage
            val sessionId = "artifacts-${UUID.randomUUID()}"
            val turnId = "turn-${UUID.randomUUID()}"
            storage.withTransaction {
                storage.sessions.create(sessionId, "Artifact fixture", null, null, 1000)
                storage.turns.start(id = turnId, sessionId = sessionId, startedAt = 2000)
                // Walk the turn to a terminal COMPLETED state via valid transitions only.
                var turn = storage.turns.resolve(turnId)
                turn = storage.turns.updateState(turn, TurnState.BUILDING_CONTEXT, 0, null, null)
                turn = storage.turns.updateState(turn, TurnState.WAITING_MODEL, 0, null, null)
                turn = storage.turns.updateState(turn, TurnState.RECEIVING_MODEL, 0, null, null)
                storage.turns.updateState(turn, TurnState.COMPLETED, 0, 3000, null)
                storage.messages.append(
                    "m-user-${UUID.randomUUID()}",
                    sessionId,
                    turnId,
                    "user",
                    "text",
                    "Generate the report",
                )
                storage.messages.append(
                    "m-assistant-${UUID.randomUUID()}",
                    sessionId,
                    turnId,
                    "assistant",
                    "text",
                    "Done: the report is complete.",
                )
            }

            compose.navigateTo("artifacts")
            compose.onNodeWithTag("screen-artifacts").assertExists()

            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("artifact-row-$turnId").fetchSemanticsNodes().isNotEmpty()
            }
            // The leaf tags sit under the clickable card, so they come from the unmerged tree.
            compose
                .onNodeWithTag("artifact-title-$turnId", useUnmergedTree = true)
                .assertTextEquals("Artifact fixture")
            compose
                .onNodeWithTag("artifact-state-$turnId", useUnmergedTree = true)
                .assertTextEquals("已完成")

            // Opening the result loads the persisted summary: Share enables only once the result
            // is present, and Collect is offered because this finished turn is not yet collected.
            compose.onNodeWithTag("artifact-view-$turnId", useUnmergedTree = true).performClick()
            compose.waitUntil(5_000) {
                compose
                    .onAllNodesWithTag("artifact-share-$turnId", useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithTag("artifact-share-$turnId", useUnmergedTree = true).assertIsEnabled()
            compose.onNodeWithTag("artifact-open-$turnId", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("artifact-collect-$turnId", useUnmergedTree = true).assertIsEnabled()

            // Dismissing the result view returns cleanly to the artifacts list.
            compose.onNodeWithTag("artifact-close-$turnId", useUnmergedTree = true).performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("screen-artifacts").assertExists()
        }
    }
}
