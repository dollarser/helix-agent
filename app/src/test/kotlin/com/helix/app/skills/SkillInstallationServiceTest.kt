package com.helix.app.skills

import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.extensions.skills.SkillImportService
import com.helix.extensions.skills.SkillRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SkillInstallationServiceTest {
    @Test
    fun pickedZipInstallsTheReviewedManifest() {
        val root = Files.createTempDirectory("install-zip-test-")
        try {
            val store = WorkspaceArtifactStore(ScopeRootResolver { root.resolve("workspace") })
            store.ensureLayout("app")
            val importer = SkillImportService(root.resolve("staging"))
            val author = SkillAuthoringService(store, importer, root.resolve("temporary"))
            val bytes = java.io.ByteArrayOutputStream()
            java.util.zip.ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("example/SKILL.md"))
                zip.write("---\nname: example\ndescription: Example\n---\nBody".toByteArray())
                zip.closeEntry()
            }
            val path = author.importArchive(bytes.toByteArray().inputStream())
            val repo = SkillRepository(root.resolve("snapshots"), root.resolve("state"), root.resolve("trash"))
            val installer = SkillInstallationService(author, importer, repo, root.resolve("snapshots"))
            val hash = author.preview(path).snapshotHash
            val key = installer.install(path, hash)
            assertEquals(hash, key.snapshotHash)
            assertFalse(installer.isEnabled(key))
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun hashBindingDefaultDisabledRetryAndRestart() {
        val root = Files.createTempDirectory("install-test-")
        try {
            val store = WorkspaceArtifactStore(ScopeRootResolver { root.resolve("workspace") })
            store.ensureLayout("app")
            val importer = SkillImportService(root.resolve("staging"))
            val author = SkillAuthoringService(store, importer, root.resolve("temporary"))

            fun repository() = SkillRepository(root.resolve("snapshots"), root.resolve("state"), root.resolve("trash"))
            val repo = repository()
            val installer = SkillInstallationService(author, importer, repo, root.resolve("snapshots"))
            val path = author.saveDraft("example", "Example", "Body")
            val hash = author.preview(path).snapshotHash
            assertThrows(IllegalStateException::class.java) { installer.install(path, hash) { true } }
            assertFalse(repo.list().any { it.key.name == "example" })
            val key = installer.install(path, hash)
            assertFalse(installer.isEnabled(key))
            assertEquals(key, installer.install(path, hash))
            installer.enable(key)
            assertEquals(key, installer.install(path, hash))
            assertTrue(installer.isEnabled(key))
            assertTrue(repository().list().single { it.key == key }.enabled)
            val draft = author.loadDraft(path)
            author.saveEditedDraft(path, draft.manifest.replace("Body", "Changed"), draft.contentHash)
            assertThrows(IllegalArgumentException::class.java) { installer.install(path, hash) }
            assertEquals(1, repo.list().count { it.key.name == "example" })
            assertTrue(repo.read(key).body.contains("Body"))
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
