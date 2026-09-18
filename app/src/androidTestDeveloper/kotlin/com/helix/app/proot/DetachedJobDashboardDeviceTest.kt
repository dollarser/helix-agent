package com.helix.app.proot

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.agent.ModelStreamTerminal
import com.helix.app.agent.TurnCoordinator
import com.helix.app.agent.TurnStartSpec
import com.helix.app.chat.BackgroundTaskQuery
import com.helix.app.ui.ASYNC_UI_TIMEOUT_MILLIS
import com.helix.app.ui.navigateTo
import com.helix.app.ui.resetDeterministicUiState
import com.helix.core.model.ModelEvent
import com.helix.core.model.SystemClock
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Room and UI projection fixtures; no Runtime process or Provider request is started. */
class DetachedJobDashboardDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = ApplicationProvider.getApplicationContext<HelixApplication>()

    @Test fun oldPendingJobSurvivesCollectedTurnAndRecentHistoryLimits() {
        val name = "dashboard-${UUID.randomUUID()}"
        val root = File(context.cacheDir, name)
        val storage = HelixStorage.open(context, name, File(root, "content"))
        try {
            val old = seed(storage)
            assertTrue(storage.turns.collectResult(old, System.currentTimeMillis()))
            val latest = seed(storage)
            storage.withTransaction {
                repeat(201) { storage.turns.start("later-$it", latest, Long.MAX_VALUE - 300 + it) }
            }
            assertFalse(BackgroundTaskQuery(storage).read().any { it.id == old })
            assertTrue(storage.toolCalls.detachedJobCandidates(1).any { it.callId == old })
            assertEquals(
                CommandDetailState.SUBMITTED,
                DetachedJobDashboard.read(storage).single { it.callId == old }.state,
            )
            terminal(storage, old, settled = false)
            assertTrue(DetachedJobDashboard.read(storage).single { it.callId == old }.settlementPending)
            settle(storage, old)
            assertFalse(storage.toolCalls.detachedJobCandidates(1).any { it.callId == old })
        } finally {
            storage.close()
            context.deleteDatabase(name)
            root.deleteRecursively()
        }
    }

    @Test fun preparedOnlyIsUnknownAndTerminalReceiptCannotBeSettledByTurnCompletion() {
        val name = "dashboard-${UUID.randomUUID()}"
        val root = File(context.cacheDir, name)
        val storage = HelixStorage.open(context, name, File(root, "content"))
        try {
            val id = seed(storage, accepted = false)
            val before = storage.auditEvents.listByCorrelation(id)
            assertEquals(CommandDetailState.UNKNOWN, DetachedJobDashboard.read(storage).single().state)
            assertEquals(before, storage.auditEvents.listByCorrelation(id))
            terminal(storage, id, settled = false)
            val pending = DetachedJobDashboard.read(storage).single()
            assertEquals(CommandDetailState.SUCCEEDED, pending.state)
            assertTrue(pending.settlementPending)
            settle(storage, id)
            assertFalse(DetachedJobDashboard.read(storage).single().settlementPending)
        } finally {
            storage.close()
            context.deleteDatabase(name)
            root.deleteRecursively()
        }
    }

    @Test fun tasksJobOpensOriginalCommandAfterItsTurnHasBeenCollected() {
        val storage = context.appContainer.storage
        val id = seed(storage)
        storage.turns.collectResult(id, System.currentTimeMillis())
        val before = storage.auditEvents.listByCorrelation(id)
        compose.resetDeterministicUiState()
        compose.navigateTo("tasks")
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            context.appContainer.chatService.backgroundJobs.value
                .any { it.callId == id }
        }
        compose.onNodeWithTag("screen-tasks").performScrollToNode(hasTestTag("tasks-job-detail-$id"))
        compose.onNodeWithTag("tasks-job-pending-$id").assertIsDisplayed()
        compose.onNodeWithTag("tasks-job-detail-$id").performClick()
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag("command-detail-state-submitted").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("command-detail-settlement-pending").assertIsDisplayed()
        assertEquals(before, storage.auditEvents.listByCorrelation(id))
        storage.sessions.archive(id, System.currentTimeMillis())
    }

    @Test fun malformedJobIdentityIsVisibleAsUnknownAndDetailsDoNotThrow() {
        val name = "dashboard-${UUID.randomUUID()}"
        val root = File(context.cacheDir, name)
        val storage = HelixStorage.open(context, name, File(root, "content"))
        try {
            val id = seed(storage, malformed = true)
            assertEquals(CommandDetailState.UNKNOWN, DetachedJobDashboard.read(storage).single().state)
            assertEquals(
                CommandDetailState.READ_FAILED,
                requireNotNull(CommandResultBrowser.browseSync(storage, id, id)).state,
            )
        } finally {
            storage.close()
            context.deleteDatabase(name)
            root.deleteRecursively()
        }
    }

    @Test fun missingStartBodyDoesNotBreakTheListOrHideTerminalProof() {
        val name = "dashboard-${UUID.randomUUID()}"
        val root = File(context.cacheDir, name)
        val storage = HelixStorage.open(context, name, File(root, "content"))
        try {
            val id = seed(storage)
            val ref =
                com.helix.core.storage.content.ContentRef.parse(
                    requireNotNull(storage.toolResults.byToolCall(id)?.contentRef),
                )
            assertTrue(File(File(root, "content"), ref.relativePath).delete())
            assertEquals(CommandDetailState.UNKNOWN, DetachedJobDashboard.read(storage).single().state)
            assertEquals(
                CommandDetailState.UNKNOWN,
                requireNotNull(CommandResultBrowser.browseSync(storage, id, id)).state,
            )
            terminal(storage, id, settled = false)
            val job = DetachedJobDashboard.read(storage).single()
            assertEquals(CommandDetailState.SUCCEEDED, job.state)
            assertTrue(job.settlementPending)
            val detail = requireNotNull(CommandResultBrowser.browseSync(storage, id, id))
            assertEquals(CommandDetailState.SUCCEEDED, detail.state)
            assertTrue(detail.settlementPending)
        } finally {
            storage.close()
            context.deleteDatabase(name)
            root.deleteRecursively()
        }
    }

    private fun seed(
        storage: HelixStorage,
        accepted: Boolean = true,
        malformed: Boolean = false,
    ): String {
        val id = UUID.randomUUID().toString()
        storage.sessions.create(id, "Background fixture", null, null, 1)
        TurnCoordinator
            .start(
                storage,
                SystemClock(),
                { UUID.randomUUID().toString() },
                TurnStartSpec(id, id, "$id-model", "fixture", "Job projection"),
            ).apply {
                beginModelStream().apply(ModelEvent.TextDelta("Turn complete"))
                terminalize(ModelStreamTerminal(TurnState.COMPLETED, null))
            }
        storage.toolCalls.append(id, id, id, DetachedJobTools.START, "1", """{"script":"sleep 30"}""", "COMPLETED")
        storage.auditEvents.append(
            "proot-job-$id",
            id,
            "proot.job_prepared",
            "platform",
            buildJsonObject {
                put("version", 3)
                put("executionMode", "DETACHED")
                put("sessionId", id)
                put("turnId", id)
                put("toolCallId", id)
                put("jobId", if (malformed) id else jobId(id))
                put("executionId", id)
                put("inputManifestSha256", "a".repeat(64))
            }.toString(),
            2,
        )
        if (accepted) storage.toolResults.append("$id-result", id, "SUCCEEDED", "Accepted", """{"accepted":true}""")
        return id
    }

    private fun terminal(
        storage: HelixStorage,
        id: String,
        settled: Boolean,
    ) {
        storage.auditEvents.append(
            "proot-terminal-$id",
            id,
            "proot.job_terminal",
            "platform",
            buildJsonObject {
                put("version", 1)
                put("jobId", jobId(id))
                put("terminalCommit", "b".repeat(64))
                put("state", "SUCCEEDED")
                put("exitCode", 0)
            }.toString(),
            3,
        )
        if (settled) settle(storage, id)
    }

    private fun settle(
        storage: HelixStorage,
        id: String,
    ) {
        storage.auditEvents.append("proot-settled-$id", id, "proot.job_settled", "platform", "b".repeat(64), 4)
    }

    private fun jobId(id: String) = "job_${id.replace("-", "").take(12)}"
}
