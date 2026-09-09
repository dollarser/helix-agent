package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.goal.toRuntimeGoal
import com.helix.app.goal.toStoredGoal
import com.helix.app.proot.ProotResultStore
import com.helix.core.model.CriterionVerificationBinding
import com.helix.core.model.CriterionVerificationMethod
import com.helix.core.model.GoalBudgets
import com.helix.core.model.GoalState
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.content.FileContentStore
import com.helix.runtime.proot.core.JobManifest
import com.helix.runtime.proot.core.JobManifestCodec
import com.helix.runtime.proot.core.JobManifestEntry
import com.helix.runtime.proot.core.JobZipWriter
import com.helix.runtime.proot.ipc.ProotJobRecord
import com.helix.runtime.proot.ipc.ProotJobState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class ProotGoalReviewUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun archivePreviewReviewAndExplicitContinueCompleteWithoutNewTurn() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val service = app.appContainer.chatService
        val storage = app.appContainer.storage
        val session = "archive-review-${UUID.randomUUID()}"
        storage.sessions.create(session, "Archive review", null, null, System.currentTimeMillis())
        service.openSession(session)
        val goal =
            runBlocking {
                service.createGoal("Review archive", listOf("Inspect file"), GoalBudgets(5, 10, 10000, 60000, 60000, 0))
            }
        val scratch = File(app.cacheDir, session)
        try {
            seed(storage, goal, session)
            persistArchive(app, goal, scratch)
            val criterion =
                storage.goals
                    .resolve(goal)
                    .criteria
                    .single()
                    .id
            render(service, goal, criterion)
            val tag = "evidence-source-${goal}result.txt"
            compose.waitUntil(10000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag(tag).performClick()
            compose.waitUntil(
                10000,
            ) { compose.onAllNodesWithTag("evidence-confirm").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("evidence-content").performScrollToNode(hasText(CONTENT))
            compose.onNodeWithText(CONTENT).assertIsDisplayed()
            compose.onNodeWithTag("evidence-confirm").performClick()
            compose.waitUntil(10000) {
                storage.goals
                    .resolve(goal)
                    .criteria
                    .single()
                    .pendingReview != null
            }
            assertEquals("PAUSED", storage.goals.resolve(goal).state)
            assertEquals(1, storage.goalRuns.listByGoal(goal).size)
            compose.onNodeWithTag("goal-continue-$goal").performScrollTo().performClick()
            compose.waitUntil(10000) { storage.goals.resolve(goal).state == "COMPLETED" }
            assertCompletedEvidence(storage, goal, session)
        } finally {
            service.closeSession()
            service.dismissBlocked()
            storage.goals.delete(goal)
            val deleted = storage.deleteSessionPermanently(session)
            deleted.unreferencedWorkspacePaths.forEach { File(app.filesDir, "workspaces/app/$it").delete() }
            scratch.deleteRecursively()
        }
    }

    private fun assertCompletedEvidence(
        storage: HelixStorage,
        goal: String,
        session: String,
    ) {
        val evidence =
            storage.goals
                .resolve(goal)
                .criteria
                .single()
                .evidence
        requireNotNull(evidence)
        assertTrue(requireNotNull(evidence.artifactRef).value.startsWith("goal-archive-"))
        assertEquals(CriterionVerificationMethod.MANUAL_REVIEW, evidence.verification?.method)
        assertEquals(1, storage.turns.listBySession(session).size)
    }

    private fun render(
        service: com.helix.app.chat.ChatService,
        goal: String,
        criterion: String,
    ) {
        val reviewing = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (reviewing.value) {
                    GoalEvidenceDialog(service, goal, criterion, true) { reviewing.value = false }
                } else {
                    GoalDialog(service, "", {}, {}, selectedGoalId = goal)
                }
            }
        }
    }

    private fun seed(
        storage: HelixStorage,
        id: String,
        session: String,
    ) {
        val now = System.currentTimeMillis()
        val goal = storage.goals.resolve(id).toRuntimeGoal()
        val criterion =
            goal.criteria.single().withBinding(
                "Inspect file",
                CriterionVerificationBinding(CriterionVerificationMethod.MANUAL_REVIEW, ""),
            )
        storage.goals.updateGoal(
            goal.copy(state = GoalState.RUNNING, runCount = 1, criteria = listOf(criterion)).toStoredGoal(),
        )
        storage.goalRuns.open(id, id, "USER_OPEN", now)
        var turn = storage.turns.start(id, session, now)
        storage.goalTurnBindings.bind(id, id)
        listOf(
            TurnState.BUILDING_CONTEXT,
            TurnState.WAITING_MODEL,
            TurnState.RECEIVING_MODEL,
            TurnState.COMPLETED,
        ).forEach {
            turn = storage.turns.updateState(turn, it, 0, if (it == TurnState.COMPLETED) now else null, null)
        }
        storage.toolCalls.append(id, id, "$id-call", "code.linux.run", "1", "{}", "COMPLETED")
        val result = storage.toolResults.append(id, id, "SUCCEEDED", "Archive fixture", "{}")
        storage.toolResults.markVerified(result)
        storage.goalRuns.finish(storage.goalRuns.resolve(id), "RUN_FINISHED", now, 0, 0, 0, 0)
        storage.goals.updateGoal(storage.goals.resolve(id).copy(state = "PAUSED"))
    }

    private fun persistArchive(
        app: HelixApplication,
        id: String,
        scratch: File,
    ) {
        check(scratch.mkdirs())
        val storage = app.appContainer.storage
        val job =
            "job_" +
                UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(12)
        val payload =
            buildJsonObject {
                put("version", 1)
                put("toolCallId", "$id-call")
                put("turnId", id)
                put("jobId", job)
                put("executionId", id)
                put("inputManifestSha256", "a".repeat(64))
            }
        storage.auditEvents.append(
            "proot-job-$id-call",
            storage.turns.resolve(id).sessionId,
            "proot.job_prepared",
            "platform",
            payload.toString(),
            System.currentTimeMillis(),
        )
        val file = File(scratch, "result.txt").apply { writeText(CONTENT) }
        val manifest =
            JobManifestCodec.encode(
                JobManifest(listOf(JobManifestEntry(file.name, FileContentStore.sha256Hex(file), file.length()))),
            )
        val archive = File(scratch, "fixture.zip")
        JobZipWriter(archive.outputStream()).use {
            it.writeManifest(manifest)
            it.writeEntry(file.name, file)
        }
        val record =
            ProotJobRecord(
                job,
                id,
                "a".repeat(64),
                ProotJobState.SUCCEEDED,
                1,
                terminalAtEpochMs = 2,
                exitCode = 0,
                outputManifestSha256 = FileContentStore.sha256Hex(manifest.toByteArray()),
            )
        val results = ProotResultStore(storage, File(app.filesDir, "workspaces/app"), scratch)
        archive.inputStream().use { results.persist(id, "$id-call", record, it) }
    }

    private companion object {
        const val CONTENT = "归档验收：这是需要用户实际查看的文件内容。"
    }
}
