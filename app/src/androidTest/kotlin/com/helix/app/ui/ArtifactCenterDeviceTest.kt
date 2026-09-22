package com.helix.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.helix.app.MainActivity
import com.helix.core.model.TurnState
import com.helix.core.workspace.FileScopePath
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
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
                storage.turns.start(id = turnId, sessionId = sessionId, startedAt = System.currentTimeMillis())
                // Walk the turn to a terminal COMPLETED state via valid transitions only.
                var turn = storage.turns.resolve(turnId)
                turn = storage.turns.updateState(turn, TurnState.BUILDING_CONTEXT, 0, null, null)
                turn = storage.turns.updateState(turn, TurnState.WAITING_MODEL, 0, null, null)
                turn = storage.turns.updateState(turn, TurnState.RECEIVING_MODEL, 0, null, null)
                storage.turns.updateState(turn, TurnState.COMPLETED, 0, System.currentTimeMillis(), null)
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
                container.chatService.backgroundTasks.value
                    .any { it.id == turnId }
            }
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("artifact-row-$turnId"))
            // The leaf tags sit under the clickable card, so they come from the unmerged tree.
            compose
                .onNodeWithTag("artifact-title-$turnId", useUnmergedTree = true)
                .assertTextEquals("Artifact fixture")
            compose
                .onNodeWithTag("artifact-state-$turnId", useUnmergedTree = true)
                .assertTextEquals("已完成")

            // Opening the result loads the persisted summary: Share enables only once the result
            // is present, and Collect is offered because this finished turn is not yet collected.
            openResult(turnId)
            compose.onNodeWithTag("artifact-share-$turnId", useUnmergedTree = true).assertIsEnabled()
            compose.onNodeWithTag("artifact-open-$turnId", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("artifact-collect-$turnId", useUnmergedTree = true).assertIsEnabled()

            // Dismissing the result view returns cleanly to the artifacts list.
            compose.onNodeWithTag("artifact-close-$turnId", useUnmergedTree = true).performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("screen-artifacts").assertExists()
        }
    }

    private fun openResult(turnId: String) {
        compose
            .onNodeWithTag(
                "artifact-view-$turnId",
                useUnmergedTree = true,
            ).performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.waitUntil(5_000) {
            compose
                .onAllNodesWithTag("artifact-share-$turnId", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    /**
     * The real artifact entries (doc 02 §8): a file the tools actually wrote appears in the
     * files section with its name, size/type and source session; opening the file view checks
     * the REAL file — the text previews in-app and Share is enabled. After the file is
     * deleted, reopening the same row shows the honest invalidation and disables Share (the
     * row outlives the file).
     */
    @Suppress("LongMethod") // One linear walkthrough: seed, list, open, invalidate, reopen.
    @Test
    fun aToolWrittenFileListsPreviewsAndInvalidatesWhenGone() {
        runBlocking {
            compose.resetDeterministicUiState()
            val container = compose.container()
            val storage = container.storage
            val sessionId = "artifact-file-${UUID.randomUUID()}"
            val turnId = "turn-file-${UUID.randomUUID()}"
            val artifactId = "artifact-file-${UUID.randomUUID()}"
            val name = "report-${UUID.randomUUID()}.txt"
            val relativePath = "output/$name"
            // v16 stored form: the row carries the full `scope:` ref; the UI and readers parse it
            // back via FileScopePath.fromModelReference. The file itself stays at the bare path.
            val storedRef = FileScopePath("app", relativePath).toModelReference()
            val bytes = "line one of the delivered report\nline two".toByteArray()
            val file =
                File(compose.activity.filesDir, "workspaces/app/$relativePath").apply {
                    requireNotNull(parentFile).mkdirs()
                    writeBytes(bytes)
                }
            storage.withTransaction {
                storage.sessions.create(sessionId, "File fixture", null, null, 1000)
                storage.artifacts.registerOrRefresh(
                    artifactId,
                    sessionId,
                    storedRef,
                    "text/plain",
                    bytes.size.toLong(),
                    sha256Hex(bytes),
                    turnId,
                    file,
                )
            }

            compose.navigateTo("artifacts")
            compose.waitUntil(10_000) {
                compose
                    .onAllNodesWithTag("artifact-file-row-$artifactId")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose
                .onNodeWithTag("artifact-file-name-$artifactId", useUnmergedTree = true)
                .assertTextEquals(name)
            compose
                .onNodeWithTag("artifact-file-meta-$artifactId", useUnmergedTree = true)
                .performScrollTo()
                .assertIsDisplayed()

            // The file exists: in-app text preview and Share enabled.
            compose
                .onNodeWithTag("artifact-file-row-$artifactId")
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
            compose.waitUntil(5_000) {
                compose
                    .onAllNodesWithTag("artifact-file-preview", useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose
                .onNodeWithTag("artifact-file-share-$artifactId", useUnmergedTree = true)
                .assertIsEnabled()
            compose
                .onNodeWithTag("artifact-file-close-$artifactId", useUnmergedTree = true)
                .assertIsDisplayed()
                .performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("artifact-file-dialog").assertDoesNotExist()

            // The row outlives the file: after deletion, honest invalidation, Share disabled.
            check(file.delete())
            compose
                .onNodeWithTag("artifact-file-row-$artifactId")
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
            compose.onNodeWithTag("artifact-file-close-$artifactId", useUnmergedTree = true).assertIsDisplayed()
            compose.waitUntil(5_000) {
                compose
                    .onAllNodesWithTag("artifact-file-missing", useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose
                .onNodeWithTag("artifact-file-share-$artifactId", useUnmergedTree = true)
                .assertIsNotEnabled()
            compose
                .onNodeWithTag("artifact-file-close-$artifactId", useUnmergedTree = true)
                .performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("screen-artifacts").assertExists()
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
