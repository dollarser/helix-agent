package com.helix.app.goal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.app.chat.GoalRunSettlement
import com.helix.app.proot.LinuxRunTool
import com.helix.app.proot.ProotResultStore
import com.helix.core.model.Clock
import com.helix.core.model.CriterionVerificationBinding
import com.helix.core.model.CriterionVerificationMethod
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.content.FileContentStore
import com.helix.runtime.proot.core.JobManifest
import com.helix.runtime.proot.core.JobManifestCodec
import com.helix.runtime.proot.core.JobManifestEntry
import com.helix.runtime.proot.core.JobZipWriter
import com.helix.runtime.proot.ipc.ProotJobRecord
import com.helix.runtime.proot.ipc.ProotJobState
import com.helix.tools.framework.BuiltInToolSource
import com.helix.tools.framework.TimeNowTool
import com.helix.tools.framework.ToolRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

class GoalArchiveArtifactDeviceTest {
    private val clock =
        object : Clock {
            override fun now(): Instant = Instant.ofEpochMilli(3000)
        }

    @Test
    fun boundArchiveCompletesGoalAndDeletedSnapshotCannotBeRecreated() =
        withGoalEvidenceStorage { storage ->
            seedGoalToolEvidence(storage)
            val context = ApplicationProvider.getApplicationContext<Context>()
            val root = File(context.cacheDir, "archive-evidence-${UUID.randomUUID()}")
            try {
                val content = "真实归档文件内容😀".repeat(8000).toByteArray()
                val path = "长文件名称".repeat(12) + ".txt"
                persist(storage, root, path, content)
                val registry =
                    ToolRegistry(listOf(BuiltInToolSource(listOf(TimeNowTool.descriptor(), LinuxRunTool.descriptor()))))
                val archives = GoalArchiveArtifactStore(storage, root, GoalToolEvidenceReader(storage, registry))
                assertEquals(listOf(path), archives.paths("goal", "stored-linux"))
                val access = GoalCriterionAccess(storage, registry, root, clock) { UUID.randomUUID().toString() }
                assertEquals(path, access.candidates("goal", "session").single { it.archivePath != null }.archivePath)
                val preview = access.preview("goal", "session", "c1", "stored-linux", archivePath = path)
                assertEquals(content.toString(Charsets.UTF_8), preview.body)
                val artifact = archives.capture("goal", "stored-linux", path)
                val reference =
                    com.helix.core.model
                        .ArtifactRef(artifact.id)
                assertTrue(reference.value.length <= 128)
                assertArrayEquals(content, archives.read("goal", "stored-linux", reference).second)
                assertThrows(IllegalArgumentException::class.java) { archives.read("goal", "call", reference) }
                val goal = storage.goals.resolve("goal").toRuntimeGoal()
                val binding = CriterionVerificationBinding(CriterionVerificationMethod.ARTIFACT_SHA256, artifact.sha256)
                storage.goals.updateGoal(
                    goal
                        .copy(
                            criteria = listOf(goal.criteria.single().withBinding("Archive bytes", binding)),
                        ).toStoredGoal(),
                )
                val verifier = GoalCompletionVerifier(storage, registry, root, clock) { UUID.randomUUID().toString() }
                GoalRunSettlement(storage, clock) { UUID.randomUUID().toString() }.settle("turn", verifier::refresh)
                assertEquals("COMPLETED", storage.goals.resolve("goal").state)
                assertEquals(
                    reference,
                    storage.goals
                        .resolve("goal")
                        .criteria
                        .single()
                        .evidence
                        ?.artifactRef,
                )
                val originalArchive = File(root, ".helix/proot-results/job_0123456789ab.zip")
                val originalBytes = originalArchive.readBytes()
                originalArchive.writeBytes(originalBytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })
                assertThrows(IllegalArgumentException::class.java) { archives.read("goal", "stored-linux", reference) }
                originalArchive.writeBytes(originalBytes)
                assertArrayEquals(content, archives.read("goal", "stored-linux", reference).second)
                assertTrue(File(root, artifact.relativePath).delete())
                assertThrows(IllegalArgumentException::class.java) { archives.capture("goal", "stored-linux", path) }
            } finally {
                root.deleteRecursively()
            }
        }

    private fun persist(
        storage: HelixStorage,
        root: File,
        path: String,
        content: ByteArray,
    ) {
        check(root.mkdirs())
        storage.toolCalls.append("stored-linux", "turn", "linux-call", "code.linux.run", "1", "{}", "COMPLETED")
        val result = storage.toolResults.append("linux-result", "stored-linux", "SUCCEEDED", "Archive result", "{}")
        storage.toolResults.markVerified(result)
        storage.auditEvents.append(
            "proot-job-linux-call",
            "session",
            "proot.job_prepared",
            "platform",
            """{"version":1,"toolCallId":"linux-call","turnId":"turn","jobId":"job_0123456789ab",
            "executionId":"execution","inputManifestSha256":"${"a".repeat(64)}"}""",
            3,
        )
        val file = File(root, "input-fixture").apply { writeBytes(content) }
        val manifest =
            JobManifestCodec.encode(
                JobManifest(listOf(JobManifestEntry(path, FileContentStore.sha256Hex(content), content.size.toLong()))),
            )
        val archive = File(root, "fixture.zip")
        JobZipWriter(archive.outputStream()).use {
            it.writeManifest(manifest)
            it.writeEntry(path, file)
        }
        val record =
            ProotJobRecord(
                "job_0123456789ab",
                "execution",
                "a".repeat(64),
                ProotJobState.SUCCEEDED,
                1,
                terminalAtEpochMs = 2,
                exitCode = 0,
                outputManifestSha256 = FileContentStore.sha256Hex(manifest.toByteArray()),
            )
        archive.inputStream().use {
            ProotResultStore(storage, root, File(root, "scratch")).persist("turn", "linux-call", record, it)
        }
    }
}
