package com.helix.core.storage.repository

import com.helix.core.model.PlanArtifact
import com.helix.core.model.PlanId
import com.helix.core.model.PlanStep
import com.helix.core.storage.dao.PlanDao
import com.helix.core.storage.entity.PlanEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

/** Files own proposals; approval evidence and execution eligibility remain transactional facts. */
class PlanDocumentStorageTest {
    @get:Rule val folder = TemporaryFolder()
    private val dao = Rows()
    private val repository by lazy { PlanRepository(dao, folder.root.toPath()) }
    private val artifact =
        PlanArtifact(
            PlanId("synthetic-plan"),
            "Synthetic task",
            listOf("No external effects"),
            listOf(PlanStep("Inspect", "Inspect synthetic content")),
            listOf("Verified"),
            emptyList(),
            1,
        )
    private val file get() = folder.root.toPath().resolve("plans/synthetic-plan.json")

    @Test fun proposedContentIsAnOpenFileAndReopensWithoutNormalizedBodyRows() {
        repository.save(artifact, "READY", null)
        assertEquals(artifact.toStorageString(), Files.readString(file))
        assertEquals(artifact, PlanRepository(dao, folder.root.toPath()).resolve(artifact.id.value))
        assertNull(dao.row?.approvedSnapshot)
    }

    @Test fun changedProposalCannotBeApprovedOrSilentlyRetargetExecution() {
        repository.save(artifact, "READY", null)
        Files.writeString(file, artifact.copy(objective = "Changed elsewhere").toStorageString())
        assertThrows(IllegalStateException::class.java) { repository.review(artifact.id.value, "APPROVED", null) }
        assertEquals("READY", dao.row?.state)
        assertNull(dao.row?.approvedSnapshot)
    }

    @Test fun approvedSnapshotSurvivesExternalEditDeletionAndReopen() {
        repository.save(artifact, "READY", null)
        repository.review(artifact.id.value, "APPROVED", "synthetic-review")
        assertEquals(artifact.toStorageString(), dao.row?.approvedSnapshot)
        Files.writeString(file, artifact.copy(objective = "Changed after approval").toStorageString())
        assertEquals(artifact, repository.resolve(artifact.id.value))
        assertEquals(1, repository.transitionFromApproved(artifact.id.value, "EXECUTING", "synthetic-goal"))
        Files.delete(file)
        assertEquals(artifact, PlanRepository(dao, folder.root.toPath()).resolve(artifact.id.value))
        assertEquals(0, repository.transitionFromApproved(artifact.id.value, "EXECUTING", "another-goal"))
    }

    @Test fun missingApprovalEvidenceNeverFallsBackToTheCurrentFile() {
        repository.save(artifact, "READY", null)
        dao.row = requireNotNull(dao.row).copy(state = "APPROVED")
        assertThrows(IllegalStateException::class.java) { repository.resolve(artifact.id.value) }
    }

    @Test fun failedDatabaseInsertLeavesNoExecutionAuthorityAndCanRecoverExactIdentity() {
        dao.failInsert = true
        assertThrows(IllegalStateException::class.java) { repository.save(artifact, "READY", null) }
        assertTrue(Files.exists(file))
        assertNull(repository.resolveOrNull(artifact.id.value))
        dao.failInsert = false
        repository.save(artifact, "READY", null)
        assertEquals(artifact, repository.resolve(artifact.id.value))
        assertEquals("READY", dao.row?.state)
    }

    @Test fun referencedPlanRefusesDeletionWithoutRemovingItsContent() {
        repository.save(artifact, "READY", null)
        dao.refuseDelete = true
        assertThrows(IllegalArgumentException::class.java) { repository.delete(artifact.id.value) }
        assertEquals(artifact, repository.resolve(artifact.id.value))
        assertTrue(Files.exists(file))
        dao.refuseDelete = false
        repository.delete(artifact.id.value)
        assertFalse(Files.exists(file))
        assertNull(repository.resolveOrNull(artifact.id.value))
    }

    private class Rows : PlanDao {
        var row: PlanEntity? = null
        var failInsert = false
        var refuseDelete = false

        override fun insert(plan: PlanEntity) {
            check(!failInsert && row == null)
            row = plan
        }

        override fun byId(id: String): PlanEntity? = row?.takeIf { it.id == id }

        override fun list(): List<PlanEntity> = listOfNotNull(row)

        override fun review(
            id: String,
            state: String,
            evidenceRef: String?,
            approvedSnapshot: String?,
        ): Int {
            if (byId(id)?.state != "READY") return 0
            row =
                requireNotNull(
                    byId(id),
                ).copy(state = state, evidenceRef = evidenceRef, approvedSnapshot = approvedSnapshot)
            return 1
        }

        override fun transitionFromApproved(
            id: String,
            state: String,
            evidenceRef: String?,
        ): Int {
            val current = byId(id)?.takeIf { it.state == "APPROVED" } ?: return 0
            row = current.copy(state = state, evidenceRef = evidenceRef)
            return 1
        }

        override fun delete(id: String): Int {
            if (refuseDelete || byId(id) == null) return 0
            row = null
            return 1
        }
    }
}
