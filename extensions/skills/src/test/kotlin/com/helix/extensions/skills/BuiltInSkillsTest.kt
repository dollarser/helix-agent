package com.helix.extensions.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class BuiltInSkillsTest {
    @Test
    fun `ships standalone skills without plugin-owned UI guidance`() {
        val documents = BuiltInSkills.documents()

        assertEquals(
            listOf(
                "data-transform",
                "helix-docs",
                "mcp-installer",
                "notification-digest",
                "organize-files-preview",
                "repo-inspection",
                "skill-creator",
                "skill-installer",
                "web-research",
            ),
            documents.map { it.catalogEntry.name },
        )
        assertTrue(documents.all { it.catalogEntry.source == SkillSource.BUILT_IN })
        assertTrue(documents.all { it.body.isNotBlank() })
        assertTrue(documents.all { it.metadata["helix.built-in-version"] == "1" })
        assertTrue(documents.all { it.license == "Apache-2.0" })
        assertTrue(documents.all { it.allowedTools == null })
        assertFalse(documents.any { it.catalogEntry.name == "android-ui-task" })
        assertNull(documents.single { it.catalogEntry.name == "notification-digest" }.compatibility)
        val help = documents.single { it.catalogEntry.name == "helix-docs" }
        assertTrue(help.resources.containsKey("references/manifest.json"))
        assertTrue(help.resources.containsKey("references/index.md"))
        assertTrue(help.resources.keys.any { it.startsWith("references/sections/") })
        assertTrue(help.resources.values.sumOf { it.toByteArray(Charsets.UTF_8).size } < 512 * 1024)
        val manifest = help.resources.getValue("references/manifest.json")
        assertEquals(HelixUserGuide.revision, helpRevision(manifest))
        assertTrue(!help.rawContent.contains("{{DOCS_REVISION}}"))
        assertTrue(help.rawContent.contains(HelixUserGuide.revision))
        assertEquals(
            help.resources.keys.count { it.startsWith("references/sections/") },
            Regex("\"status\":\"implemented\"").findAll(manifest).count(),
        )
    }

    @Test
    fun `bundled help is readable only by enabled hash-pinned model skill`() {
        val root = Files.createTempDirectory("helix-docs")
        val repository = SkillRepository(root.resolve("snapshots"), root.resolve("state"), root.resolve("trash"))
        val key = repository.list("session").single { it.key.name == "helix-docs" }.key

        assertEquals(true, repository.listForModel("session", null).single { it.key == key }.enabled)
        val manifest = repository.readResource(key, "references/manifest.json", "session")
        assertEquals("utf-8", manifest.encoding)
        assertEquals(HelixUserGuide.revision, helpRevision(manifest.content))
        assertTrue(
            repository
                .readResource(key, "references/sections/channels.md", "session")
                .content
                .contains("development preview"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            repository.readResource(key.copy(snapshotHash = "wrong"), "references/index.md", "session")
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.readResource(key, "../SKILL.md", "session")
        }

        repository.setEnabled(key, false, SkillEnablementScope.SESSION, "session")
        assertThrows(IllegalArgumentException::class.java) {
            repository.readResource(key, "references/index.md", "session")
        }
    }

    private fun helpRevision(manifest: String): String =
        Regex(""""revision"\s*:\s*"([a-f0-9]{64})"""")
            .find(manifest)
            ?.groupValues
            ?.get(1)
            ?: error("Help manifest has no valid revision")
}
