package com.helix.app.goal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.chat.GoalRunSettlement
import com.helix.core.model.Clock
import com.helix.core.model.CriterionVerificationBinding
import com.helix.core.model.CriterionVerificationMethod
import com.helix.core.model.ExecutionTargetType
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.content.FileContentStore
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.tools.files.EditTool
import com.helix.tools.files.WriteTool
import com.helix.tools.framework.BuiltInToolSource
import com.helix.tools.framework.CancelSignal
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.TimeNowTool
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GoalWrittenArtifactDeviceTest {
    private val clock =
        object : Clock {
            override fun now(): Instant = Instant.ofEpochMilli(3000)
        }

    @Test fun realWriteBytesCompleteHashCriterionAndDeletedSnapshotStaysMissing() = verifyWritten(false)

    @Test fun realEditBytesCompleteAndSnapshotSurvivesLaterFileChanges() = verifyWritten(true)

    private fun verifyWritten(edited: Boolean) =
        withGoalEvidenceStorage { storage ->
            seedGoalToolEvidence(storage)
            val context = ApplicationProvider.getApplicationContext<Context>()
            val root = File(context.cacheDir, "written-evidence-${UUID.randomUUID()}")
            try {
                val content = "实际产出文件\n"
                val registry =
                    ToolRegistry(
                        listOf(
                            BuiltInToolSource(
                                listOf(TimeNowTool.descriptor(), WriteTool.descriptor(), EditTool.descriptor()),
                            ),
                        ),
                    )
                writeResult(storage, root, content, edited)
                val goal = storage.goals.resolve("goal").toRuntimeGoal()
                val binding =
                    CriterionVerificationBinding(
                        CriterionVerificationMethod.ARTIFACT_SHA256,
                        FileContentStore.sha256Hex(content.toByteArray()),
                    )
                val criterion = goal.criteria.single().withBinding("Written bytes", binding)
                storage.goals.updateGoal(goal.copy(criteria = listOf(criterion)).toStoredGoal())
                val source = ScopedEditedArtifactReader(WorkspaceArtifactStore(ScopeRootResolver { root.toPath() }))
                val verifier =
                    GoalCompletionVerifier(storage, registry, root, clock, { UUID.randomUUID().toString() }, source)
                GoalRunSettlement(storage, clock) { UUID.randomUUID().toString() }.settle("turn", verifier::refresh)
                val completed = storage.goals.resolve("goal")
                assertEquals("COMPLETED", completed.state)
                val evidence = completed.criteria.single().evidence
                val reference = requireNotNull(evidence?.artifactRef)
                val artifacts = GoalToolArtifactStore(storage, root, GoalToolEvidenceReader(storage, registry))
                val (artifact, bytes) = artifacts.read("goal", "written", reference)
                assertTrue(artifact.id.startsWith("goal-written-"))
                assertArrayEquals(content.toByteArray(), bytes)
                assertEquals(content, File(root, "output/result.txt").readText())
                if (edited) {
                    File(root, "output/result.txt").writeText("changed after completion")
                    assertArrayEquals(content.toByteArray(), artifacts.read("goal", "written", reference).second)
                }
                assertTrue(File(root, artifact.relativePath).delete())
                assertThrows(IllegalArgumentException::class.java) { artifacts.capture("goal", "written", true) }
            } finally {
                root.deleteRecursively()
            }
        }

    private fun writeResult(
        storage: HelixStorage,
        root: File,
        content: String,
        edited: Boolean,
    ) {
        val store = WorkspaceArtifactStore(ScopeRootResolver { root.toPath() })
        store.ensureLayout("ws")
        if (edited) File(root, "output/result.txt").writeText("before edit")
        val tool = if (edited) "edit" else "write"
        val args =
            buildJsonObject {
                put("path", JsonPrimitive("scope:ws:output/result.txt"))
                if (edited) {
                    put("oldText", JsonPrimitive("before edit"))
                    put("newText", JsonPrimitive(content))
                    put("expectedSha256", JsonPrimitive(FileContentStore.sha256Hex("before edit".toByteArray())))
                } else {
                    put("content", JsonPrimitive(content))
                }
            }
        val call =
            ExecutableToolCall(
                "written",
                tool,
                "1",
                args,
                ExecutionTargetType.LOCAL_ANDROID,
                Instant.now().plusSeconds(30),
                object : CancelSignal {
                    override fun isCancelled() = false
                },
            )
        val result =
            (
                if (edited) {
                    EditTool.executor(
                        store,
                    )
                } else {
                    WriteTool.executor(store)
                }
            ).execute(call) as ToolExecutorResult.Completed
        storage.toolCalls.append("written", "turn", "written", tool, "1", args.toString(), "COMPLETED")
        val row =
            storage.toolResults.append(
                "written-result",
                "written",
                "SUCCEEDED",
                "Write result",
                result.output.toString(),
            )
        storage.toolResults.markVerified(row)
    }
}
