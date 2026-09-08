package com.helix.app.connector

import com.helix.extensions.skills.SkillImportService
import com.helix.extensions.skills.connector.ConnectorPackageReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** User-supplied marketplace archives; no source credentials or network execution. */
class WorkBuddySuppliedArchiveTest {
    @Test
    fun marketplaceArchivesPreserveAllSkillsAndReferences() {
        val source = System.getenv("HELIX_WORKBUDDY_SAMPLE_DIR")
        assumeTrue("requires local WorkBuddy samples", !source.isNullOrBlank())
        val expected =
            mapOf(
                "github" to setOf("github"),
                "kling-ai-plugin" to
                    setOf("kling-ai", "kling-ai-generate-image", "kling-ai-generate-video"),
            )
        val root = Files.createTempDirectory("workbuddy-sample-")
        try {
            expected.forEach { (name, names) ->
                val bundle = ConnectorPackageReader().readZip(Path.of(requireNotNull(source), "$name.zip"))
                assertEquals(listOf(name), bundle.endpoints.map { it.name })
                assertEquals(names.size, bundle.skills.size)
                val importer = SkillImportService(root.resolve("staging"))
                val imported =
                    bundle.skills
                        .map { skill ->
                            val directory = root.resolve(name).resolve(skill.directory)
                            skill.files.forEach { (relative, bytes) ->
                                val target = directory.resolve(ConnectorPackageReader.safePath(relative))
                                Files.createDirectories(target.parent)
                                Files.write(target, bytes)
                            }
                            val staged = importer.stageDirectory(directory)
                            val snapshot = importer.commit(staged, root.resolve("snapshots"))
                            assertTrue(snapshot.snapshotHash.isNotBlank())
                            skill.files.forEach { (relative, bytes) ->
                                val saved = Files.readAllBytes(snapshot.directory.resolve(relative))
                                assertTrue(bytes.contentEquals(saved))
                            }
                            snapshot.name
                        }.toSet()
                assertEquals(names, imported)
                println("WorkBuddy $name: ${bundle.skills.size} skills, ${bundle.endpoints.size} endpoint")
            }
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }
    }
}
