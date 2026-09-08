package com.helix.core.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class WorkspacePrivacyArtifactTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun ownedResultAndEvidenceFilesCanBeDeletedIdempotently() {
        val root = temporary.newFolder().toPath()
        val store = WorkspaceArtifactStore(ScopeRootResolver { root })
        for (name in listOf(
            ".helix/subscription-results/job_123.json",
            ".helix/goal-evidence/proof.txt",
            "output/result.txt",
        )) {
            val target = root.resolve(name)
            Files.createDirectories(target.parent)
            Files.writeString(target, "private artifact")
            assertTrue(store.deletePermanentlyForPrivacy(FileScopePath("app", name)))
            assertFalse(Files.exists(target))
            assertFalse(store.deletePermanentlyForPrivacy(FileScopePath("app", name)))
        }
    }

    @Test fun metadataAndDirectoriesRemainProtected() {
        val root = temporary.newFolder().toPath()
        val store = WorkspaceArtifactStore(ScopeRootResolver { root })
        for (name in listOf(
            ".helix/metadata.json",
            ".helix/executions/state.json",
            ".helix/subscription-results-other/a.json",
        )) {
            val target = root.resolve(name)
            Files.createDirectories(target.parent)
            Files.writeString(target, "keep")
            assertThrows(IllegalArgumentException::class.java) {
                store.deletePermanentlyForPrivacy(FileScopePath("app", name))
            }
            assertTrue(Files.exists(target))
        }
        val directory = ".helix/goal-evidence/directory"
        Files.createDirectories(root.resolve(directory))
        assertThrows(IllegalArgumentException::class.java) {
            store.deletePermanentlyForPrivacy(FileScopePath("app", directory))
        }
    }

    @Test fun privateArtifactSymlinkCannotDeleteOutsideFile() {
        val root = temporary.newFolder().toPath()
        val outside = temporary.newFile().toPath()
        val name = ".helix/subscription-results/link.json"
        Files.createDirectories(root.resolve(name).parent)
        Files.createSymbolicLink(root.resolve(name), outside)
        val store = WorkspaceArtifactStore(ScopeRootResolver { root })
        assertTrue(runCatching { store.deletePermanentlyForPrivacy(FileScopePath("app", name)) }.isFailure)
        assertTrue(Files.exists(outside))
    }
}
