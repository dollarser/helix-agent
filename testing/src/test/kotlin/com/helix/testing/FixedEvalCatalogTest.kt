package com.helix.testing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class FixedEvalCatalogTest {
    private val root = Path.of(requireNotNull(System.getProperty("helix.eval.dir")))
    private val dataset = root.resolve("fixed-evals.tsv")
    private val injectionMatrix = root.resolve("prompt-injection.tsv")

    @Test
    fun catalogHasTheRequiredFixedCoverageAndUniqueIds() {
        val rows = rows()
        assertTrue(rows.size >= 40)
        assertEquals(rows.size, rows.map { it[0] }.toSet().size)
        assertEquals(
            setOf(
                "chat",
                "plan",
                "goal",
                "provider",
                "file",
                "javascript",
                "browser",
                "mcp",
                "a2a",
                "skill",
                "accessibility",
                "root",
            ),
            rows.map { it[1] }.toSet(),
        )
        assertEquals(
            setOf("OPENAI_RESPONSES", "OPENAI_CHAT_COMPLETIONS", "ANTHROPIC_MESSAGES"),
            rows.map { it[3] }.toSet(),
        )
        rows.forEach { row ->
            assertEquals(6, row.size)
            assertTrue(row.all(String::isNotBlank))
            assertFalse(row[4].contains('\n'))
        }
    }

    @Test
    fun metadataTemplateRequiresEveryReproductionFact() {
        val text = Files.readString(root.resolve("run-metadata.template.json"))
        listOf(
            "datasetSha256",
            "provider",
            "model",
            "providerReportedVersion",
            "protocol",
            "temperature",
            "promptSha256",
            "toolVersions",
            "device",
            "dateUtc",
            "gitCommit",
            "result",
            "evidence",
        ).forEach { assertTrue("missing $it", text.contains("\"$it\"")) }
    }

    @Test
    fun datasetDigestIsStable() {
        val expected = Files.readString(root.resolve("fixed-evals.sha256")).trim().substringBefore(' ')
        val actual = sha256(Files.readAllBytes(dataset))
        assertEquals(expected, actual)
    }

    @Test
    fun promptInjectionMatrixCoversEveryRequiredIngressAndUsesAuthorityInvariants() {
        val rows =
            Files
                .readAllLines(injectionMatrix)
                .drop(1)
                .filter(String::isNotBlank)
                .map { it.split('\t') }
        assertTrue(rows.size >= 16)
        assertEquals(rows.size, rows.map { it[0] }.toSet().size)
        assertEquals(setOf("WEB", "FILE", "MCP", "A2A", "SKILL", "NOTIFICATION"), rows.map { it[1] }.toSet())
        assertTrue(rows.all { it.size == 5 })
        assertTrue(rows.all { it[4] == "no_scope_registry_or_approval_change" })
        assertTrue(rows.any { it[0].startsWith("pi-filename-") })
        assertTrue(rows.any { it[2] == "fake_system_tag" })
        assertTrue(rows.any { it[2] == "fake_tool_tag" })
        val expected = Files.readString(root.resolve("prompt-injection.sha256")).trim().substringBefore(' ')
        assertEquals(expected, sha256(Files.readAllBytes(injectionMatrix)))
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun rows(): List<List<String>> =
        Files
            .readAllLines(dataset)
            .drop(1)
            .filter(String::isNotBlank)
            .map { it.split('\t') }
}
