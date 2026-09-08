package com.helix.app.goal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.core.agent.Criterion
import com.helix.core.model.ArtifactRef
import com.helix.core.model.Clock
import com.helix.core.model.CriterionVerificationBinding
import com.helix.core.model.CriterionVerificationMethod
import com.helix.core.model.Sha256
import com.helix.core.storage.HelixStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GoalCriterionVerifierDeviceTest {
    @Test
    fun capturesIdempotentSnapshotAndMatchesLiteral() =
        fixture { _, store, verifier, _ ->
            val artifact = store.capture("goal", "call")
            assertEquals(artifact, store.capture("goal", "call"))
            val criterion = criterion(CriterionVerificationMethod.ARTIFACT_UTF8_CONTAINS, "真实输出")
            val evidence = requireNotNull(verifier.automatic("goal", criterion, "call", ArtifactRef(artifact.id)))
            assertTrue(criterion.withEvidence(evidence).isSatisfied)
            assertEquals(artifact.sha256, evidence.verification?.contentHash?.hex)
            assertEquals("真实输出", store.read("goal", "call", ArtifactRef(artifact.id)).second.toString(Charsets.UTF_8))
        }

    @Test
    fun deletedSnapshotIsNotRecreatedByReadOrCapture() =
        fixture { _, store, _, workspace ->
            val artifact = store.capture("goal", "call")
            val file = File(workspace, artifact.relativePath)
            assertTrue(file.delete())
            assertThrows(IllegalArgumentException::class.java) { store.read("goal", "call", ArtifactRef(artifact.id)) }
            assertThrows(IllegalArgumentException::class.java) { store.capture("goal", "call") }
            assertTrue(!file.exists())
        }

    @Test
    fun alteredSnapshotIsRejectedWithoutOverwritingIt() =
        fixture { _, store, _, workspace ->
            val artifact = store.capture("goal", "call")
            val file = File(workspace, artifact.relativePath)
            file.writeText("篡改内容")
            assertThrows(IllegalArgumentException::class.java) { store.read("goal", "call", ArtifactRef(artifact.id)) }
            assertThrows(IllegalArgumentException::class.java) { store.capture("goal", "call") }
            assertEquals("篡改内容", file.readText())
        }

    @Test
    fun hashAndLocalToolRulesRequireTheirExactUserBinding() =
        fixture { _, store, verifier, _ ->
            val artifact = store.capture("goal", "call")
            val hash = criterion(CriterionVerificationMethod.ARTIFACT_SHA256, artifact.sha256)
            assertTrue(verifier.automatic("goal", hash, "call", ArtifactRef(artifact.id)) != null)
            val wrong = criterion(CriterionVerificationMethod.ARTIFACT_SHA256, "0".repeat(64))
            assertNull(verifier.automatic("goal", wrong, "call", ArtifactRef(artifact.id)))
            val local = criterion(CriterionVerificationMethod.LOCAL_TOOL_SUCCESS, "time.now")
            assertTrue(verifier.automatic("goal", local, "call") != null)
            val other = criterion(CriterionVerificationMethod.LOCAL_TOOL_SUCCESS, "read")
            assertNull(verifier.automatic("goal", other, "call"))
        }

    @Test
    fun manualReviewRequiresExactDisplayedSourceAndBinding() =
        fixture { storage, _, verifier, _ ->
            val criterion = criterion(CriterionVerificationMethod.MANUAL_REVIEW, "")
            val source = goalEvidenceReader(storage).read("goal", "call")
            val selection =
                GoalEvidenceReview(
                    "call",
                    requireNotNull(criterion.binding).hash(criterion.id, criterion.description),
                    source.hash,
                )
            assertNull(verifier.automatic("goal", criterion, "call"))
            assertTrue(criterion.withEvidence(verifier.review("goal", criterion, selection)).isSatisfied)
            assertThrows(IllegalArgumentException::class.java) {
                verifier.review("goal", criterion, selection.copy(bindingHash = Sha256("0".repeat(64))))
            }
            assertThrows(IllegalArgumentException::class.java) {
                verifier.review("goal", criterion, selection.copy(sourceHash = Sha256("0".repeat(64))))
            }
        }

    private fun criterion(
        method: CriterionVerificationMethod,
        argument: String,
    ): Criterion = Criterion("c1", "Review actual result", binding = CriterionVerificationBinding(method, argument))

    private fun fixture(block: (HelixStorage, GoalToolArtifactStore, GoalCriterionVerifier, File) -> Unit) {
        withGoalEvidenceStorage { storage ->
            seedGoalToolEvidence(storage)
            val context = ApplicationProvider.getApplicationContext<Context>()
            val workspace = File(context.cacheDir, "goal-artifact-${UUID.randomUUID()}")
            val reader = goalEvidenceReader(storage)
            val store = GoalToolArtifactStore(storage, workspace, reader)
            val clock =
                object : Clock {
                    override fun now(): Instant = Instant.ofEpochMilli(3000)
                }
            try {
                block(storage, store, GoalCriterionVerifier(reader, store, clock), workspace)
            } finally {
                workspace.deleteRecursively()
            }
        }
    }
}
