package com.helix.extensions.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files

class SkillCatalogLoaderTest {
    @Test
    fun `catalog is deterministic and contains discovery fields only`() {
        val root = Files.createTempDirectory("skill-catalog")
        writeSkill(root, "zeta", "Last skill", "secret body")
        writeSkill(root, "alpha", "First skill", "private body")
        Files.createDirectory(root.resolve("invalid"))

        val catalog = SkillCatalogLoader().scan(root, SkillSource.PROJECT)

        assertEquals(listOf("alpha", "zeta"), catalog.entries.map { it.name })
        assertEquals(listOf("invalid"), catalog.diagnostics.map { it.directoryName })
        assertTrue(catalog.entries.all { it.source == SkillSource.PROJECT })
        val rendered = catalog.entries.toString()
        assertFalse(rendered.contains("secret body"))
        assertFalse(rendered.contains("private body"))
    }

    @Test
    fun `does not follow symlinked skill directories or files`() {
        val root = Files.createTempDirectory("skill-catalog")
        val outside = Files.createTempDirectory("skill-outside")
        writeSkill(outside, "external", "External", "body")
        val linkedDirectory = root.resolve("external")
        val linkedFileDirectory = Files.createDirectory(root.resolve("linked-file"))
        try {
            Files.createSymbolicLink(linkedDirectory, outside.resolve("external"))
            Files.createSymbolicLink(
                linkedFileDirectory.resolve("SKILL.md"),
                outside.resolve("external").resolve("SKILL.md"),
            )
        } catch (_: UnsupportedOperationException) {
            assumeTrue("Symbolic links are not supported", false)
        }

        val catalog = SkillCatalogLoader().scan(root, SkillSource.USER_IMPORTED)

        assertTrue(catalog.entries.isEmpty())
        assertEquals(listOf("linked-file"), catalog.diagnostics.map { it.directoryName })
    }

    @Test
    fun `rejects a symlink catalog root`() {
        val parent = Files.createTempDirectory("skill-catalog-parent")
        val root = Files.createTempDirectory("skill-catalog-target")
        val link = parent.resolve("catalog-link")
        try {
            Files.createSymbolicLink(link, root)
        } catch (_: UnsupportedOperationException) {
            assumeTrue("Symbolic links are not supported", false)
        }

        assertThrows(IllegalArgumentException::class.java) {
            SkillCatalogLoader().scan(link, SkillSource.BUILT_IN)
        }
    }

    private fun writeSkill(
        root: java.nio.file.Path,
        name: String,
        description: String,
        body: String,
    ) {
        val directory = Files.createDirectory(root.resolve(name))
        Files.writeString(
            directory.resolve("SKILL.md"),
            """
            ---
            name: $name
            description: $description
            ---
            $body
            """.trimIndent(),
        )
    }
}
