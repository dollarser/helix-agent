package com.helix.extensions.skills.connector

import com.helix.extensions.skills.InvalidSkillException
import com.helix.extensions.skills.SkillLoader
import com.helix.extensions.skills.SkillSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ConnectorCompatibilityTest {
    @Test
    fun foreignDirectoryUsesDeclaredNameWithoutChangingSourceBytes() {
        val bytes = "---\nname: github\ndescription: Example\n---\nOriginal body".toByteArray()
        val reader = ConnectorPackageReader()
        val bundle = reader.parse(mapOf("skill/SKILL.md" to bytes))
        assertEquals("github", bundle.skills.single().directory)
        assertTrue(
            bytes.contentEquals(
                bundle.skills
                    .single()
                    .files
                    .getValue("SKILL.md"),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            reader.parse(mapOf("one/SKILL.md" to bytes, "two/SKILL.md" to bytes))
        }
        for (invalid in listOf("../outside", "nested/name", "/absolute", ".", "..")) {
            assertThrows(IllegalArgumentException::class.java) {
                reader.parse(
                    mapOf("skill/SKILL.md" to bytes.toString(Charsets.UTF_8).replace("github", invalid).toByteArray()),
                )
            }
        }
    }

    @Test
    fun qwenEnvelopeAndFilenameImportEndpointsWithoutSourcePolicyOrCredentials() {
        val config = """{"schemaVersion":"qwenwork.mcp/v1","dynamic":{"mode":"replace","servers":{
            "ext:docs":{"url":"https://example.com/mcp","headers":{"Authorization":"fixture-secret"}}
            }},"policy":{"allowed":["*"],"conflict":"prefer-dynamic"}}"""
        val bundle = ConnectorPackageReader().parse(mapOf("qwenwork-mcp-sample.json" to config.toByteArray()))
        assertEquals(1, bundle.endpoints.size)
        assertTrue(bundle.endpoints.single().needsCredential)
        assertFalse(bundle.toString().contains("fixture-secret"))
        assertTrue(bundle.diagnostics.contains("QWENWORK_CONFIG_SNAPSHOT"))
        assertEquals(bundle.endpoints, ConnectorPackageReader().readJson(config.toByteArray()).endpoints)
        val future =
            ConnectorPackageReader().readJson(
                config.replace("qwenwork.mcp/v1", "qwenwork.mcp/v9").toByteArray(),
            )
        assertTrue(future.endpoints.isEmpty())
        assertTrue(future.diagnostics.contains("UNSUPPORTED_MCP_SCHEMA"))
        assertThrows(IllegalArgumentException::class.java) {
            ConnectorPackageReader().parse(
                mapOf(
                    "qwenwork-mcp.json" to config.toByteArray(),
                    "mcp.json" to """{"ext:docs":{"url":"https://other.example/mcp"}}""".toByteArray(),
                ),
            )
        }
        val unknown = ConnectorPackageReader().parse(mapOf("custom-mcp.json" to config.toByteArray()))
        assertTrue(unknown.diagnostics.contains("UNRECOGNIZED_MCP_CONFIG:custom-mcp.json"))
    }

    /** Official format regression, not a captured WorkBuddy export or live service acceptance. */
    @Test
    fun workBuddyHttpAliasAndStaticHeadersKeepIndependentCredentialBoundary() {
        val reader = ConnectorPackageReader()
        val config = """{"mcpServers":{"sample":{"type":"streamableHttp",
            "url":"https://example.com/mcp","staticHeaders":{"Authorization":"fixture-secret"},
            "disabledTools":["write"],"enabled":true}}}"""
        val bundle = reader.readJson(config.toByteArray())
        assertEquals("https://example.com/mcp", bundle.endpoints.single().url)
        assertTrue(bundle.endpoints.single().needsCredential)
        assertTrue(bundle.diagnostics.contains("AUTH_REQUIRES_CONFIGURATION:sample"))
        assertTrue(bundle.diagnostics.contains("UNSUPPORTED_SERVER_OPTIONS:sample"))
        assertFalse(bundle.toString().contains("fixture-secret"))
        for (alias in listOf("http", "streamable-http", "streamable_http")) {
            assertEquals(
                bundle.endpoints,
                reader.readJson(config.replace("streamableHttp", alias).toByteArray()).endpoints,
            )
        }
        for (unsupported in listOf("sse", "StreamableHttp", "future")) {
            val result = reader.readJson(config.replace("streamableHttp", unsupported).toByteArray())
            assertTrue(result.endpoints.isEmpty())
            assertTrue(result.diagnostics.contains("UNSUPPORTED_TRANSPORT:sample"))
        }
        val anonymous =
            reader.readJson(
                """{"sample":{"type":"streamableHttp","url":"https://example.com/mcp"}}""".toByteArray(),
            )
        assertFalse(anonymous.endpoints.single().needsCredential)
    }

    @Test
    fun metadataProjectionPreservesOriginalAndStrictStandaloneContract() {
        val raw =
            (
                "---\nname: sample\ndescription: Sample\nmetadata:\n" +
                    "  requires:\n    bins: [dws]\n  version: 2\n---\nOriginal body.\n"
            ).toByteArray()
        val originalFiles = mapOf("sample/SKILL.md" to raw)
        val bundle = ConnectorPackageReader().parse(originalFiles)
        val files = bundle.skills.single().files
        assertTrue(raw.contentEquals(files.getValue(ConnectorSkillMetadataAdapter.ORIGINAL)))
        assertEquals(ConnectorPackageReader.digest(originalFiles), bundle.contentHash)
        assertTrue(bundle.diagnostics.contains("SKILL_REQUIRES_BINARY:sample:dws"))
        val directory = Files.createTempDirectory("metadata-").resolve("sample")
        try {
            Files.createDirectories(directory)
            Files.write(directory.resolve("SKILL.md"), raw)
            assertThrows(InvalidSkillException::class.java) { SkillLoader().load(directory, SkillSource.USER_IMPORTED) }
            Files.write(directory.resolve("SKILL.md"), files.getValue("SKILL.md"))
            val loaded = SkillLoader().load(directory, SkillSource.USER_IMPORTED)
            assertEquals("Original body.\n", loaded.body)
            assertEquals("2", loaded.metadata["version"])
            assertTrue(Json.parseToJsonElement(loaded.metadata.getValue("requires")).jsonObject.containsKey("bins"))
            assertThrows(IllegalArgumentException::class.java) {
                ConnectorPackageReader().parse(
                    originalFiles + ("sample/${ConnectorSkillMetadataAdapter.ORIGINAL}" to raw),
                )
            }
        } finally {
            Files.walk(directory.parent).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
            }
        }
    }
}
