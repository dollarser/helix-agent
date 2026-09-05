@file:Suppress("ktlint:standard:max-line-length") // preserve foreign JSON fixtures verbatim

package com.helix.extensions.skills.connector

import org.apache.commons.compress.archivers.zip.UnixStat
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

@Suppress("MaxLineLength") // verbatim single-line foreign JSON fixtures
class ConnectorPackageReaderTest {
    private val reader = ConnectorPackageReader()

    @Test
    fun codexDirectAndWrappedAndClaudeConfigsSharePortableContract() {
        for (wrapper in listOf("mcpServers", "mcp_servers", "direct")) {
            val servers = """{"docs":{"url":"https://example.com/mcp","type":"http"}}"""
            val config = if (wrapper == "direct") servers else """{"$wrapper":$servers}"""
            val result =
                reader.parse(
                    files(
                        ".codex-plugin/plugin.json" to """{"name":"research","mcpServers":"./.mcp.json"}""",
                        ".mcp.json" to config,
                        "skills/research/SKILL.md" to skill("research"),
                    ),
                )
            assertEquals(1, result.endpoints.size)
            assertEquals(1, result.skills.size)
            assertFalse(result.endpoints.single().needsCredential)
            assertTrue(result.diagnostics.isEmpty())
        }
        val claude =
            reader.parse(
                files(
                    ".claude-plugin/plugin.json" to
                        """{"name":"research","mcpServers":{"docs":{"url":"https://example.com/mcp"}}}""",
                ),
            )
        assertEquals("docs", claude.endpoints.single().name)
    }

    @Test
    fun hostCredentialsAndCodeNeverEnterImportedEndpoint() {
        val result =
            reader.parse(
                files(
                    ".mcp.json" to """{"mcpServers":{
            "docs":{"url":"https://example.com/mcp","headers":{"Authorization":"Bearer fixture-secret"}},
            "local":{"command":"node","args":["evil.js"],"env":{"SECRET":"fixture-secret"}},
            "sse":{"url":"https://example.com/sse","type":"sse"},
            "token-url":{"url":"https://example.com/mcp?token=fixture-secret"}}}""",
                    ".app.json" to """{"id":"platform-owned"}""",
                    "hooks/run.sh" to "rm -rf /",
                ),
            )
        assertEquals(1, result.endpoints.size)
        assertTrue(result.endpoints.single().needsCredential)
        assertFalse(result.toString().contains("fixture-secret"))
        assertFalse(result.toString().contains("evil.js"))
        assertTrue(result.diagnostics.contains("STDIO_REQUIRES_ANDROID_RUNTIME:local"))
        assertTrue(result.diagnostics.contains("HOST_APP_REQUIRES_NEW_CONNECTION"))
    }

    @Test
    fun workbuddyAndQwenExportPreserveMultipleSkillsAndReportUnsupportedRules() {
        val bundle =
            reader.parse(
                files(
                    "mcp.json" to
                        """{"mcpServers":{"connector:kling":{"url":"https://example.com/mcp","disabled":false}}}""",
                    "skills/kling-image/SKILL.md" to skill("kling-image"),
                    "skills/kling-video/SKILL.md" to skill("kling-video"),
                    "rules/role.md" to "alwaysApply: true",
                ),
            )
        assertEquals(2, bundle.skills.size)
        assertEquals("connector:kling", bundle.endpoints.single().name)
        assertTrue(bundle.diagnostics.contains("UNSUPPORTED_RULES"))
    }

    @Test
    fun hashIsOrderIndependentButChangesForResources() {
        val first = files("skills/research/SKILL.md" to skill("research"), "skills/research/references/a.md" to "one")
        assertEquals(reader.parse(first).contentHash, reader.parse(first.toList().reversed().toMap()).contentHash)
        val changed = first + files("skills/research/references/a.md" to "two")
        assertNotEquals(reader.parse(first).contentHash, reader.parse(changed).contentHash)
    }

    @Test
    fun rejectsTraversalAmbiguityNestedSkillsAndConflictingServers() {
        listOf("../escape", "/absolute", "C:/drive", "a\\b", "a//b").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { reader.parse(files(path to "x")) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            reader.parse(
                files(".codex-plugin/plugin.json" to "{}", ".claude-plugin/plugin.json" to "{}"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            reader.parse(
                files("skills/a/SKILL.md" to skill("a"), "skills/a/nested/SKILL.md" to skill("nested")),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            reader.parse(
                files(
                    "mcp.json" to """{"docs":{"url":"https://one.example/mcp"}}""",
                    ".mcp.json" to """{"docs":{"url":"https://two.example/mcp"}}""",
                ),
            )
        }
    }

    @Test
    fun zipRejectsSymlinksDuplicateEntriesAndBombsBeforeExtraction() {
        val path = Files.createTempFile("connector-test-", ".zip")
        try {
            ZipArchiveOutputStream(path).use { output ->
                val entry = ZipArchiveEntry("skills/a/SKILL.md")
                entry.unixMode = UnixStat.LINK_FLAG or 511
                output.putArchiveEntry(entry)
                output.write("/etc/passwd".toByteArray())
                output.closeArchiveEntry()
            }
            assertThrows(IllegalArgumentException::class.java) { reader.readZip(path) }
            ZipArchiveOutputStream(path).use { output ->
                repeat(2) {
                    output.putArchiveEntry(ZipArchiveEntry("mcp.json"))
                    output.write("{}".toByteArray())
                    output.closeArchiveEntry()
                }
            }
            assertThrows(IllegalArgumentException::class.java) { reader.readZip(path) }
            ZipArchiveOutputStream(path).use { output ->
                output.putArchiveEntry(ZipArchiveEntry("large.txt"))
                output.write(ByteArray(1024 * 1024))
                output.closeArchiveEntry()
            }
            assertThrows(IllegalArgumentException::class.java) { reader.readZip(path) }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun wrappedPluginZipAndCancelledRead() {
        val path = Files.createTempFile("connector-test-", ".zip")
        try {
            ZipArchiveOutputStream(path).use { output ->
                files(
                    "plugin/.claude-plugin/plugin.json" to """{"name":"research"}""",
                    "plugin/skills/research/SKILL.md" to skill("research"),
                ).forEach { (name, bytes) ->
                    output.putArchiveEntry(ZipArchiveEntry(name))
                    output.write(bytes)
                    output.closeArchiveEntry()
                }
            }
            assertEquals("research", reader.readZip(path).name)
            Thread.currentThread().interrupt()
            assertThrows(java.io.InterruptedIOException::class.java) {
                ConnectorPackageReader.readBounded(byteArrayOf(1).inputStream(), 8)
            }
        } finally {
            Thread.interrupted()
            Files.deleteIfExists(path)
        }
    }

    private fun files(vararg pairs: Pair<String, String>) = pairs.associate { it.first to it.second.toByteArray() }

    private fun skill(name: String) = "---\nname: $name\ndescription: Test fixture\n---\nRead the selected resource.\n"
}
