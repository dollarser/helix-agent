package com.helix.extensions.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class SkillRepositoryTest {
    @Test
    fun `built-ins default globally enabled and session override does not change global state`() {
        val roots = roots()
        val repository = repository(roots)
        val key = repository.list().single { it.key.name == "repo-inspection" }.key

        assertTrue(repository.list().single { it.key == key }.enabled)
        repository.setEnabled(key, false, SkillEnablementScope.SESSION, sessionId = "session-a")

        assertFalse(repository.list("session-a").single { it.key == key }.enabled)
        assertTrue(repository.list("session-b").single { it.key == key }.enabled)
        assertThrows(IllegalArgumentException::class.java) { repository.read(key, "session-a") }
        assertTrue(repository.read(key, "session-b").body.contains("Repository inspection"))
    }

    @Test
    fun `global enablement persists across repository recreation`() {
        val roots = roots()
        val repository = repository(roots)
        val key = repository.list().single { it.key.name == "web-research" }.key
        repository.setEnabled(key, false, SkillEnablementScope.GLOBAL)

        val recreated = repository(roots)

        assertFalse(recreated.list().single { it.key == key }.enabled)
    }

    @Test
    fun `imported snapshot defaults disabled and reads only bounded references and assets`() {
        val roots = roots()
        val source = importedSource(roots.root)
        val importService = SkillImportService(roots.staging)
        val snapshot = importService.commit(importService.stageDirectory(source), roots.snapshots)
        val repository = repository(roots)
        val key = repository.registerSnapshot(snapshot)

        assertFalse(repository.list().single { it.key == key }.enabled)
        assertThrows(IllegalArgumentException::class.java) { repository.read(key) }
        repository.setEnabled(key, true, SkillEnablementScope.SESSION, "session")
        assertTrue(repository.read(key, "session").body.contains("Imported body"))

        val reference = repository.readResource(key, "references/guide.txt", "session")
        assertEquals("utf-8", reference.encoding)
        assertEquals("guide", reference.content)
        assertEquals(64, reference.sha256.length)
        val asset = repository.readResource(key, "assets/blob.bin", "session")
        assertEquals("base64", asset.encoding)

        listOf("../outside", "/absolute", "scripts/run.js", "references/link/escape")
            .forEach { path ->
                assertThrows(IllegalArgumentException::class.java) {
                    repository.readResource(key, path, "session")
                }
            }
    }

    @Test
    fun `remove moves only imported snapshot to recoverable trash`() {
        val roots = roots()
        val source = importedSource(roots.root)
        val importService = SkillImportService(roots.staging)
        val snapshot = importService.commit(importService.stageDirectory(source), roots.snapshots)
        val repository = repository(roots)
        val key = repository.registerSnapshot(snapshot)

        val trash = repository.remove(key)

        assertFalse(Files.exists(snapshot.directory))
        assertTrue(Files.exists(trash.resolve("SKILL.md")))
        assertFalse(repository.list().any { it.key == key })
        val builtIn = repository.list().first { it.key.source == SkillSource.BUILT_IN }.key
        assertThrows(IllegalArgumentException::class.java) { repository.remove(builtIn) }
    }

    private fun repository(roots: Roots): SkillRepository = SkillRepository(roots.snapshots, roots.state, roots.trash)

    private fun importedSource(root: Path): Path {
        val directory = Files.createDirectory(root.resolve("imported-skill"))
        Files.writeString(
            directory.resolve("SKILL.md"),
            """
            ---
            name: imported-skill
            description: Imported test skill.
            allowed-tools: Bash(root:*)
            ---
            # Imported body
            """.trimIndent(),
        )
        write(directory, "references/guide.txt", "guide".toByteArray())
        write(directory, "assets/blob.bin", byteArrayOf(0xC3.toByte(), 0x28))
        write(directory, "scripts/run.js", "throw new Error('must not auto-run')".toByteArray())
        return directory
    }

    private fun write(
        root: Path,
        relative: String,
        bytes: ByteArray,
    ) {
        val target = root.resolve(relative)
        Files.createDirectories(target.parent)
        Files.write(target, bytes)
    }

    private fun roots(): Roots {
        val root = Files.createTempDirectory("skill-repository")
        return Roots(
            root = root,
            staging = root.resolve("staging"),
            snapshots = root.resolve("snapshots"),
            state = root.resolve("state/enablement.txt"),
            trash = root.resolve("trash"),
        )
    }

    private data class Roots(
        val root: Path,
        val staging: Path,
        val snapshots: Path,
        val state: Path,
        val trash: Path,
    )
}
