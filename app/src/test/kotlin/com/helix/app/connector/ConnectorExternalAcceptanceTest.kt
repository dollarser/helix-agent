package com.helix.app.connector

import com.helix.extensions.mcp.McpClients
import com.helix.extensions.mcp.McpResultBlock
import com.helix.extensions.skills.connector.ConnectorPackageReader
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** Opt-in external evidence: regular offline unit runs never contact public services. */
class ConnectorExternalAcceptanceTest {
    private fun sampleRoot(): Path {
        val directory = System.getenv("HELIX_CONNECTOR_ACCEPTANCE_DIR")
        assumeTrue("HXA-125 external acceptance is opt-in", !directory.isNullOrBlank())
        return Path.of(requireNotNull(directory))
    }

    @Test
    fun pinnedPublicSourceConfigsUseProductionReader() {
        val root = sampleRoot()
        val reader = ConnectorPackageReader()

        fun sample(name: String) = Files.readAllBytes(root.resolve(name))
        val linear = reader.readJson(sample("anthropic-linear.json"))
        assertEquals("https://mcp.linear.app/mcp", linear.endpoints.single().url)
        val github = reader.readJson(sample("anthropic-github.json"))
        assertTrue(github.endpoints.single().needsCredential)
        assertFalse(github.toString().contains("GITHUB_PERSONAL_ACCESS_TOKEN"))
        val playwright = reader.readJson(sample("anthropic-playwright.json"))
        assertTrue(playwright.endpoints.isEmpty())
        assertTrue(playwright.diagnostics.any { it.startsWith("STDIO_REQUIRES_ANDROID_RUNTIME") })
        for (host in listOf("codex", "claude")) {
            val bundle =
                reader.parse(
                    mapOf(
                        ".$host-plugin/plugin.json" to sample("cloudflare-$host.json"),
                        ".mcp.json" to sample("cloudflare-mcp.json"),
                    ),
                )
            assertEquals("https://mcp.cloudflare.com/mcp", bundle.endpoints.single().url)
        }
        // A full multi-host repository currently requires explicit host selection before packaging.
        assertThrows(IllegalArgumentException::class.java) {
            reader.parse(
                mapOf(
                    ".codex-plugin/plugin.json" to sample("cloudflare-codex.json"),
                    ".claude-plugin/plugin.json" to sample("cloudflare-claude.json"),
                    ".mcp.json" to sample("cloudflare-mcp.json"),
                ),
            )
        }
    }

    @Test
    fun publicDocumentationServiceNegotiatesAndAnswersReadOnlyQuery() {
        sampleRoot()
        runBlocking {
            withTimeout(90_000) {
                val session = McpClients.sdk("helix-hxa-125", "1").connect("https://docs.mcp.cloudflare.com/mcp")
                try {
                    val metadata = session.snapshotMetadata()
                    val tool = metadata.tools.single { it.name == "search_cloudflare_documentation" }
                    val adapted =
                        com.helix.app.mcp.McpToolSchemaAdapter
                            .adapt(tool)
                    assertEquals(tool.schemaHash, adapted.schemaHash)
                    val result =
                        session.callTool(
                            tool.name,
                            buildJsonObject { put("query", JsonPrimitive("Cloudflare Workers documentation")) },
                        )
                    assertFalse(result.isError)
                    assertTrue(result.blocks.filterIsInstance<McpResultBlock.Text>().any { it.text.isNotBlank() })
                    println(
                        "HXA-125 public SDK smoke: protocol=${session.server.negotiatedProtocolVersion}, " +
                            "tools=${metadata.tools.size}",
                    )
                } finally {
                    session.close()
                }
            }
        }
    }
}
