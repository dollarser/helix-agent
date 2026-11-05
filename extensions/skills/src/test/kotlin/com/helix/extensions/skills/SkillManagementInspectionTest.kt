package com.helix.extensions.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SkillManagementInspectionTest {
    @Test fun disabledContentIsInspectableWithoutActivationOrExecution() {
        val root = Files.createTempDirectory("skill-inspection")
        val doc =
            SkillLoader().loadBuiltIn(
                "---\nname: sample\ndescription: Sample\n---\nGuide",
                "sample",
                mapOf(
                    "scripts/example.py" to "raise RuntimeError('never execute')",
                    "assets/template.txt" to "template",
                ),
            )
        val repo =
            SkillRepository(
                root.resolve("snapshots"),
                root.resolve("state"),
                root.resolve("trash"),
                listOf(doc),
            )
        val key = repo.list().single().key
        repo.setEnabled(key, false, SkillEnablementScope.GLOBAL)
        val files = repo.inspectFiles(key)
        assertEquals(setOf(SkillFileKind.SCRIPT, SkillFileKind.ASSET), files.map { it.kind }.toSet())
        assertTrue(!repo.list().single().enabled)
        assertThrows(IllegalArgumentException::class.java) { repo.read(key, "s", "t") }
        assertThrows(IllegalArgumentException::class.java) { repo.prepareUserActivation(key, "s", "") }
    }

    @Test fun changedImportedFilesFailInspectionInsteadOfShowingOldSnapshot() {
        val root = Files.createTempDirectory("skill-inspection-change")
        val source = Files.createDirectories(root.resolve("sample"))
        Files.writeString(source.resolve("SKILL.md"), "---\nname: sample\ndescription: Sample\n---\nGuide")
        val scripts = Files.createDirectories(source.resolve("scripts"))
        Files.writeString(scripts.resolve("example.py"), "print(1)")
        val importer = SkillImportService(root.resolve("staging"))
        val ref = importer.commit(importer.stageDirectory(source), root.resolve("installed"))
        val repo = SkillRepository(root.resolve("installed"), root.resolve("state"), root.resolve("trash"), emptyList())
        val key = repo.registerSnapshot(ref)
        assertEquals(1, repo.inspectFiles(key).count { it.kind == SkillFileKind.SCRIPT })
        Files.writeString(ref.directory.resolve("scripts/example.py"), "print(2)")
        assertThrows(IllegalArgumentException::class.java) { repo.inspectFiles(key) }
    }
}
