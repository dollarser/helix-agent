package com.helix.app.goal

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.recovery.RecoveryCoordinatorApp
import com.helix.core.model.Clock
import com.helix.core.model.CriterionVerificationBinding
import com.helix.core.model.CriterionVerificationMethod
import com.helix.core.model.GoalState
import com.helix.core.storage.HelixStorage
import com.helix.tools.framework.BuiltInToolSource
import com.helix.tools.framework.TimeNowTool
import com.helix.tools.framework.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.UUID

/** Host kills the actual instrumented process; fixture evidence is not real-model acceptance. */
@RunWith(AndroidJUnit4::class)
class GoalEvidenceProcessKillDeviceTest {
    @Test
    fun reviewAndCompletionSurviveProcessDeathWithoutReplay() {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("goal.evidence.phase") ?: "control"
        val boundary = args.getString("goal.evidence.boundary") ?: "staged"
        require(phase in setOf("control", "prepare", "recover"))
        require(boundary in setOf("staged", "uncommitted", "committed", "review-uncommitted", "reading"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.filesDir, "goal-evidence-kill-fixture")
        if (phase != "recover") {
            context.deleteDatabase(DATABASE)
            directory.deleteRecursively()
            directory.mkdirs()
        }
        val storage = HelixStorage.open(context, DATABASE, File(directory, "content"))
        try {
            if (phase != "recover") stage(storage, directory, phase == "prepare" && boundary == "review-uncommitted")
            if (phase == "prepare") {
                prepareKill(storage, directory, boundary)
            } else {
                recover(
                    storage,
                    directory,
                    phase == "recover" && boundary == "committed",
                    phase == "recover" && boundary in setOf("review-uncommitted", "reading"),
                )
            }
        } finally {
            storage.close()
        }
        if (phase != "prepare") {
            context.deleteDatabase(DATABASE)
            directory.deleteRecursively()
        }
    }

    private fun stage(
        storage: HelixStorage,
        directory: File,
        killBeforeCommit: Boolean,
    ) {
        seedGoalToolEvidence(storage)
        val goal = storage.goals.resolve("goal").toRuntimeGoal()
        val criterion =
            goal.criteria.single().withBinding(
                "Review actual source",
                CriterionVerificationBinding(CriterionVerificationMethod.MANUAL_REVIEW, ""),
            )
        storage.goalRuns.finish(storage.goalRuns.resolve("run"), "RUN_FINISHED", 2000, 0, 0, 0, 0)
        storage.goals.updateGoal(goal.copy(state = GoalState.PAUSED, criteria = listOf(criterion)).toStoredGoal())
        val reader = GoalToolEvidenceReader(storage, registry())
        val verifier = GoalCriterionVerifier(reader, GoalToolArtifactStore(storage, directory, reader), clock)
        storage.withTransaction {
            GoalCriterionEditor(storage, verifier, clock, ::newId).stageReview(
                "goal",
                "c1",
                GoalEvidenceReview(
                    "call",
                    requireNotNull(criterion.binding).hash(criterion.id, criterion.description),
                    reader.read("goal", "call").hash,
                ),
            )
            if (killBeforeCommit) awaitKill("review-uncommitted")
        }
        assertParked(storage)
    }

    private fun prepareKill(
        storage: HelixStorage,
        directory: File,
        boundary: String,
    ) {
        when (boundary) {
            "reading" -> {
                val reader = GoalToolEvidenceReader(storage, registry())
                val store = GoalToolArtifactStore(storage, directory, reader)
                val artifact = store.capture("goal", "call")
                storage.goals.updateGoal(
                    storage.goals.resolve("goal").toRuntimeGoal().let { goal ->
                        goal.copy(criteria = goal.criteria.map { it.copy(pendingReview = null) }).toStoredGoal()
                    },
                )
                val reading =
                    GoalToolArtifactStore(storage, directory, reader, readCheckpoint = { awaitKill(boundary) })
                reading.read(
                    "goal",
                    "call",
                    com.helix.core.model
                        .ArtifactRef(artifact.id),
                )
                error("Read kill checkpoint returned")
            }

            "uncommitted" -> {
                storage.withTransaction {
                    assertTrue(service(storage, directory).tryComplete("goal", "session"))
                    assertEquals("COMPLETED", storage.goals.resolve("goal").state)
                    awaitKill(boundary)
                }
            }

            "committed" -> {
                assertTrue(service(storage, directory).tryComplete("goal", "session"))
                awaitKill(boundary)
            }

            else -> {
                awaitKill(boundary)
            }
        }
    }

    private fun recover(
        storage: HelixStorage,
        directory: File,
        committed: Boolean,
        reviewNotCommitted: Boolean,
    ) {
        val before = storage.goals.resolve("goal")
        RecoveryCoordinatorApp(storage, clock).recover()
        assertEquals(before, storage.goals.resolve("goal"))
        if (reviewNotCommitted) {
            assertEquals("PAUSED", before.state)
            assertNull(before.criteria.single().pendingReview)
            assertNull(before.criteria.single().evidence)
            assertEquals(1, storage.goalRuns.listByGoal("goal").size)
            org.junit.Assert.assertFalse(service(storage, directory).tryComplete("goal", "session"))
            assertEquals(before, storage.goals.resolve("goal"))
            assertEquals(1, storage.turns.listBySession("session").size)
            assertEquals(1, storage.toolCalls.listByTurn("turn").size)
            assertTrue(storage.modelCalls.listByTurn("turn").isEmpty())
            for (artifact in storage.artifacts.listBySession("session")) {
                val reader = GoalToolEvidenceReader(storage, registry())
                val bytes =
                    GoalToolArtifactStore(storage, directory, reader)
                        .read(
                            "goal",
                            "call",
                            com.helix.core.model
                                .ArtifactRef(artifact.id),
                        ).second
                assertEquals("真实输出", bytes.toString(Charsets.UTF_8))
            }
            return
        }
        if (committed) assertCompleted(storage) else assertParked(storage)
        assertTrue(service(storage, directory).tryComplete("goal", "session"))
        assertCompleted(storage)
        val completed = storage.goals.resolve("goal")
        assertTrue(service(storage, directory).tryComplete("goal", "session"))
        assertEquals(completed, storage.goals.resolve("goal"))
        assertCompleted(storage)
    }

    private fun assertParked(storage: HelixStorage) {
        val goal = storage.goals.resolve("goal")
        assertEquals("PAUSED", goal.state)
        assertEquals(1, goal.runCount)
        assertNotNull(goal.criteria.single().pendingReview)
        assertNull(goal.criteria.single().evidence)
        assertEquals(1, storage.goalRuns.listByGoal("goal").size)
        assertEquals(0L, goal.totalTokens)
    }

    private fun assertCompleted(storage: HelixStorage) {
        val goal = storage.goals.resolve("goal")
        assertEquals("COMPLETED", goal.state)
        assertEquals(2, goal.runCount)
        assertNull(goal.criteria.single().pendingReview)
        assertNotNull(goal.criteria.single().evidence)
        val run = storage.goalRuns.listByGoal("goal").single { it.id != "run" }
        assertEquals("COMPLETED", run.outcome)
        assertEquals(0, run.modelCalls)
        assertEquals(0, run.toolCalls)
        assertEquals(0L, run.tokens)
        assertEquals(0L, goal.totalTokens)
        assertEquals(1, storage.turns.listBySession("session").size)
        assertEquals(1, storage.toolCalls.listByTurn("turn").size)
        assertTrue(storage.modelCalls.listByTurn("turn").isEmpty())
    }

    private fun service(
        storage: HelixStorage,
        directory: File,
    ) = GoalEvidenceContinue(
        storage,
        GoalCompletionVerifier(storage, registry(), directory, clock, ::newId),
        clock,
        ::newId,
    )

    private fun awaitKill(boundary: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply {
                putString("stream", "GOAL_EVIDENCE_KILL_READY boundary=$boundary pid=${android.os.Process.myPid()}\n")
            },
        )
        Thread.sleep(30_000)
        error("Host did not kill the prepared process")
    }

    private fun registry() = ToolRegistry(listOf(BuiltInToolSource(listOf(TimeNowTool.descriptor()))))

    private fun newId() = UUID.randomUUID().toString()

    private companion object {
        const val DATABASE = "goal-evidence-kill.db"
        val clock =
            object : Clock {
                override fun now(): Instant = Instant.ofEpochMilli(3000)
            }
    }
}
