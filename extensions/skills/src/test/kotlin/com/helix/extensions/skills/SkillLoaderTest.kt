package com.helix.extensions.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class SkillLoaderTest {
    private val loader = SkillLoader()

    @Test
    fun `loads the specification frontmatter fields and retains extensions as untrusted data`() {
        val directory =
            skill(
                name = "pdf-processing",
                content =
                    """
                    ---
                    name: pdf-processing
                    description: Extract text and tables from PDF files.
                    license: Apache-2.0
                    compatibility: Requires Java 17
                    metadata:
                      author: example-org
                      version: "1"
                    allowed-tools: Bash(git:*) Read
                    vendor-extension:
                      nested: true
                    ---
                    # PDF Processing

                    This body is loaded only when the caller requests the document.
                    """.trimIndent(),
            )

        val document = loader.load(directory, SkillSource.USER_IMPORTED)

        assertEquals("pdf-processing", document.catalogEntry.name)
        assertEquals("Extract text and tables from PDF files.", document.catalogEntry.description)
        assertEquals(SkillSource.USER_IMPORTED, document.catalogEntry.source)
        assertEquals(64, document.catalogEntry.contentHash.length)
        assertEquals("Apache-2.0", document.license)
        assertEquals("Requires Java 17", document.compatibility)
        assertEquals("example-org", document.metadata["author"])
        assertEquals("1", document.metadata["version"])
        assertEquals("Bash(git:*) Read", document.allowedTools)
        assertTrue(document.additionalFields.containsKey("vendor-extension"))
        assertTrue(document.body.contains("This body is loaded"))

        // Discovery data cannot smuggle allowed-tools, body, paths, or extension fields.
        assertFalse(document.catalogEntry.toString().contains("allowed-tools"))
        assertFalse(document.catalogEntry.toString().contains("This body"))
        assertFalse(document.catalogEntry.toString().contains(directory.toString()))
    }

    @Test
    fun `accepts official name boundaries and rejects invalid names`() {
        listOf("a", "skill-1", "a".repeat(64)).forEach { validName ->
            loader.load(skill(validName, validDocument(validName)), SkillSource.BUILT_IN)
        }

        listOf("Uppercase", "-leading", "trailing-", "two--hyphens", "has space", "技能", "a".repeat(65))
            .forEach { invalidName ->
                assertThrows(InvalidSkillException::class.java) {
                    loader.load(skill(invalidName, validDocument(invalidName)), SkillSource.BUILT_IN)
                }
            }
    }

    @Test
    fun `enforces required fields and official length limits`() {
        assertInvalid("missing-name", "---\ndescription: valid\n---\n")
        assertInvalid("missing-description", "---\nname: missing-description\n---\n")
        assertInvalid("wrong-name-type", "---\nname: 7\ndescription: valid\n---\n")
        assertInvalid("blank-description", "---\nname: blank-description\ndescription: ''\n---\n")
        assertInvalid(
            "long-description",
            validDocument("long-description", description = "x".repeat(1_025)),
        )
        assertInvalid(
            "long-compatibility",
            validDocument("long-compatibility", extra = "compatibility: '${"x".repeat(501)}'"),
        )
        assertInvalid(
            "blank-compatibility",
            validDocument("blank-compatibility", extra = "compatibility: ''"),
        )
        assertInvalid(
            "numeric-metadata",
            validDocument("numeric-metadata", extra = "metadata: { version: 1 }"),
        )
        loader.load(
            skill(
                "boundary-fields",
                validDocument(
                    "boundary-fields",
                    description = "x".repeat(1_024),
                    extra = "compatibility: '${"x".repeat(500)}'",
                ),
            ),
            SkillSource.PROJECT,
        )
    }

    @Test
    fun `rejects malformed or unsafe YAML envelopes`() {
        assertInvalid("no-frontmatter", "# no frontmatter")
        assertInvalid("unclosed", "---\nname: unclosed\ndescription: no close")
        assertInvalid("not-map", "---\n- name\n- description\n---\n")
        assertInvalid("bad-yaml", "---\nname: [\ndescription: broken\n---\n")
        assertInvalid("duplicate", "---\nname: duplicate\nname: duplicate\ndescription: bad\n---\n")
        assertInvalid(
            "aliases",
            "---\nname: aliases\ndescription: bad\nmetadata: &meta {a: b}\nextension: *meta\n---\n",
        )
        assertInvalid("nul", "---\nname: nul\ndescription: bad\n---\n\u0000")
    }

    @Test
    fun `rejects malformed UTF-8 and oversized files`() {
        val malformed = Files.createTempDirectory("skill-loader").resolve("malformed")
        Files.createDirectory(malformed)
        Files.write(malformed.resolve("SKILL.md"), byteArrayOf(0xC3.toByte(), 0x28))
        assertThrows(InvalidSkillException::class.java) {
            loader.load(malformed, SkillSource.PROJECT)
        }

        val oversized = Files.createTempDirectory("skill-loader").resolve("oversized")
        Files.createDirectory(oversized)
        Files.write(oversized.resolve("SKILL.md"), ByteArray(SkillLoader.MAX_SKILL_BYTES.toInt() + 1))
        assertThrows(InvalidSkillException::class.java) {
            loader.load(oversized, SkillSource.PROJECT)
        }
    }

    @Test
    fun `content hash covers the complete exact document`() {
        val first = loader.load(skill("hash-skill", validDocument("hash-skill") + "body one"), SkillSource.PROJECT)
        val second = loader.load(skill("hash-skill", validDocument("hash-skill") + "body two"), SkillSource.PROJECT)

        assertNotEquals(first.catalogEntry.contentHash, second.catalogEntry.contentHash)
    }

    @Test
    fun `requires the declared name to match the directory`() {
        assertInvalid("directory-name", validDocument("different-name"))
    }

    @Test
    fun `accepts CRLF documents`() {
        val content = validDocument("crlf-skill").replace("\n", "\r\n")
        val document = loader.load(skill("crlf-skill", content), SkillSource.BUILT_IN)
        assertEquals("crlf-skill", document.catalogEntry.name)
    }

    private fun assertInvalid(
        directoryName: String,
        content: String,
    ) {
        assertThrows(InvalidSkillException::class.java) {
            loader.load(skill(directoryName, content), SkillSource.USER_IMPORTED)
        }
    }

    private fun skill(
        name: String,
        content: String,
    ): Path {
        val directory = Files.createTempDirectory("skill-loader").resolve(name)
        Files.createDirectory(directory)
        Files.writeString(directory.resolve("SKILL.md"), content)
        return directory
    }

    private fun validDocument(
        name: String,
        description: String = "A valid skill description.",
        extra: String = "",
    ): String =
        """
        ---
        name: $name
        description: '$description'
        $extra
        ---
        # Body
        """.trimIndent() + "\n"

    companion object {
        // Fixture semantics pinned to the Agent Skills repository revision reviewed for HXA-074.
        const val OFFICIAL_FIXTURE_REVISION = "69ef37e9424c0a7ea9dd2293b559e43ec8176379"
    }
}
