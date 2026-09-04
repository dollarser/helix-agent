package com.helix.extensions.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.text.Normalizer

/**
 * Compatibility fixtures transcribed from the official Agent Skills repository at
 * commit [OFFICIAL_COMMIT]: `skills-ref/tests/test_parser.py`,
 * `skills-ref/tests/test_validator.py`, and `docs/specification.mdx`.
 *
 * The Apache-2.0 `skills-ref` code is test evidence only. Production uses [SkillLoader]
 * and has no dependency on `skills-ref`.
 */
class OfficialAgentSkillsFixtureTest {
    @Test
    fun `official minimal and optional frontmatter fixtures parse`() {
        val minimal = loader.loadBuiltIn(OFFICIAL_MINIMAL, "my-skill")
        val optional = loader.loadBuiltIn(OFFICIAL_OPTIONAL, "pdf-processing")

        assertEquals("my-skill", minimal.catalogEntry.name)
        assertEquals("A test skill", minimal.catalogEntry.description)
        assertEquals("pdf-processing", optional.catalogEntry.name)
        assertEquals("Apache-2.0", optional.license)
        assertEquals("example-org", optional.metadata["author"])
        assertEquals("1.0", optional.metadata["version"])
    }

    @Test
    fun `official internationalized and NFKC name fixtures parse canonically`() {
        val chinese = loader.loadBuiltIn(skill("技能"), "技能")
        val russian = loader.loadBuiltIn(skill("мой-навык"), "мой-навык")
        val decomposed = "cafe\u0301"
        val composed = Normalizer.normalize(decomposed, Normalizer.Form.NFKC)
        val normalized = loader.loadBuiltIn(skill(decomposed), composed)

        assertEquals("技能", chinese.catalogEntry.name)
        assertEquals("мой-навык", russian.catalogEntry.name)
        assertEquals(composed, normalized.catalogEntry.name)
    }

    @Test
    fun `official invalid naming fixtures fail closed`() {
        listOf("MySkill", "-my-skill", "my--skill", "my_skill", "a".repeat(65)).forEach { name ->
            assertThrows(InvalidSkillException::class.java) {
                loader.loadBuiltIn(skill(name), name)
            }
        }
    }

    private fun skill(name: String): String =
        """
        ---
        name: $name
        description: A test skill
        ---
        Body
        """.trimIndent()

    private companion object {
        const val OFFICIAL_COMMIT = "69ef37e9424c0a7ea9dd2293b559e43ec8176379"
        val loader = SkillLoader()

        val OFFICIAL_MINIMAL =
            """
            ---
            name: my-skill
            description: A test skill
            ---
            # My Skill

            Instructions here.
            """.trimIndent()

        val OFFICIAL_OPTIONAL =
            """
            ---
            name: pdf-processing
            description: Extract PDF text, fill forms, merge files. Use when handling PDFs.
            license: Apache-2.0
            metadata:
              author: example-org
              version: "1.0"
            ---
            """.trimIndent()
    }
}
