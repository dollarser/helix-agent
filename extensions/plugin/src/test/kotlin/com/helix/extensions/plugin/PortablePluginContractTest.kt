package com.helix.extensions.plugin

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The normative standard and foreign-host import dialects must not be confused. */
class PortablePluginContractTest {
    private val reader = PluginPackageReader()

    @Test fun metadataOnlyAndOptionalFieldsAreValid() {
        val parsed = PluginManifestReader.parse(manifest())
        assertEquals("research", parsed.name)
        assertEquals("", parsed.version)
        assertEquals("", parsed.description)
        val result = reader.parse(mapOf("plugin.json" to manifest()))
        assertTrue("PLUGIN_NO_SUPPORTED_COMPONENTS" in result.diagnostics)
    }

    @Test fun serverLabelsDoNotInheritPluginNameConstraints() {
        val result = reader.parse(base("mcp.json" to mcp("\"Docs_v2\":${http()},\"org:Docs\":${http()}")))
        assertEquals(listOf("Docs_v2", "org:Docs"), result.endpoints.map { it.name })
    }

    @Test fun manifestNamesFollowTheStandard() {
        listOf("a", "alpha.plugin", "a-b", "a".repeat(64)).forEach { name ->
            assertEquals(name, PluginManifestReader.parse(manifest(name)).name)
        }
        listOf("", "-a", "a-", "a.", "A", "a_b", "a--b", "a..b", "a".repeat(65)).forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { PluginManifestReader.parse(manifest(name)) }
        }
    }

    @Test fun versionIsMetadataNotASemverGate() {
        val result = PluginManifestReader.parse(manifest(extra = "\"version\":\"preview release 2026\""))
        assertEquals("preview release 2026", result.version)
    }

    @Test fun knownMetadataTypesAreNotCoerced() {
        listOf(
            "\"version\":7",
            "\"description\":null",
            "\"author\":\"person\"",
            "\"author\":{\"name\":4}",
            "\"keywords\":[1]",
        ).forEach { extra ->
            assertThrows(IllegalArgumentException::class.java) { PluginManifestReader.parse(manifest(extra = extra)) }
        }
    }

    @Test fun malformedUtf8IsNotReplacementText() {
        val invalid = manifest() + byteArrayOf(0xC3.toByte(), 0x28)
        assertThrows(IllegalArgumentException::class.java) { PluginManifestReader.parse(invalid) }
    }

    @Test fun foreignOverridesNeverReplaceStandardPaths() {
        val result =
            reader.parse(
                base(
                    "plugin.json" to manifest(extra = "\"skills\":\"./elsewhere\",\"mcpServers\":\"./other.json\""),
                    "mcp.json" to mcp("\"good\":${http()}"),
                    "other.json" to mcp("\"not-loaded\":${http()}"),
                    "elsewhere/other/SKILL.md" to skill("other"),
                    ".mcp.json" to "invalid ignored JSON".toByteArray(),
                ),
            )
        assertEquals(listOf("good"), result.endpoints.map { it.name })
        assertEquals(listOf("research"), result.skills.map { it.directory })
        assertEquals(2, result.diagnostics.count { it.startsWith("PLUGIN_UNKNOWN_FIELD:") })
    }

    @Test fun missingMcpSchemaSkipsOnlyMcp() {
        val result = reader.parse(base("mcp.json" to "{\"mcpServers\":{}}".toByteArray()))
        assertTrue("PLUGIN_MCP_CONFIG_INVALID" in result.diagnostics)
        assertEquals(1, result.skills.size)
        assertTrue(result.endpoints.isEmpty())
    }

    @Test fun corruptMcpDoesNotDisableValidSkills() {
        val result = reader.parse(base("mcp.json" to "[broken".toByteArray()))
        assertEquals(listOf("research"), result.skills.map { it.directory })
        assertTrue("PLUGIN_MCP_CONFIG_INVALID" in result.diagnostics)
    }

    @Test fun oneMalformedServerDoesNotDisableAnother() {
        val config = mcp("\"good\":${http()},\"bad\":{\"url\":\"https://e.test/mcp\"}")
        val result = reader.parse(base("mcp.json" to config))
        assertEquals(listOf("good"), result.endpoints.map { it.name })
        assertTrue(result.diagnostics.any { it.startsWith("PLUGIN_MCP_SERVER_INVALID:") })
    }

    @Test fun foreignHttpTypeIsNotAStandardTransport() {
        val config = mcp("\"bad\":{\"type\":\"http\",\"url\":\"https://e.test/mcp\"}")
        val result = reader.parse(base("mcp.json" to config))
        assertTrue(result.endpoints.isEmpty())
        assertEquals(1, result.skills.size)
    }

    @Test fun invalidSkillDoesNotDisableValidSiblingOrMcp() {
        val result =
            reader.parse(
                base(
                    "skills/broken/SKILL.md" to "not frontmatter".toByteArray(),
                    "mcp.json" to mcp("\"good\":${http()}"),
                ),
            )
        assertEquals(listOf("research"), result.skills.map { it.directory })
        assertEquals(1, result.endpoints.size)
        assertTrue("PLUGIN_SKILL_INVALID:broken" in result.diagnostics)
    }

    @Test fun nestedSkillIsAResourceNotAnotherComponent() {
        val result = reader.parse(base("skills/research/examples/child/SKILL.md" to skill("child")))
        assertEquals(1, result.skills.size)
        assertTrue("examples/child/SKILL.md" in result.skills.single().files)
    }

    @Test fun nativeExtensionNeverImportsHostCode() {
        val extensions = "\"extensions\":{\"com.helix.agent\":{\"runtime\":\"mobile-use\"}}"
        val result = reader.parse(base("plugin.json" to manifest(extra = extensions)))
        assertTrue("HOST_NATIVE_COMPONENT_NOT_IMPORTED" in result.diagnostics)
        assertTrue(result.endpoints.isEmpty())
    }

    @Test fun duplicateCaseHeadersAndInjectedLinesAreInvalid() {
        listOf("\"Authorization\":\"x\",\"authorization\":\"y\"", "\"X-Test\":\"x\\r\\ny\"").forEach { header ->
            val config = "{\"type\":\"streamable-http\",\"url\":\"https://e.test/mcp\",\"headers\":{$header}}"
            val result = reader.parse(base("mcp.json" to mcp("\"bad\":$config")))
            assertTrue(result.endpoints.isEmpty())
            assertTrue(result.diagnostics.any { it.startsWith("PLUGIN_MCP_SERVER_INVALID:") })
        }
    }

    @Test fun validForeignCredentialsAreStrippedNotImported() {
        val config =
            "{\"type\":\"streamable-http\",\"url\":\"https://e.test/mcp\"," +
                "\"headers\":{\"Authorization\":\"fixture-secret\"}}"
        val result = reader.parse(base("mcp.json" to mcp("\"good\":$config")))
        assertTrue(result.endpoints.single().needsCredential)
        assertFalse(result.toString().contains("fixture-secret"))
    }

    @Test fun structuralPathCollisionIsNotALocalComponentError() {
        val files = base("skills/research" to "file".toByteArray())
        assertThrows(IllegalArgumentException::class.java) { reader.parse(files) }
    }

    private fun base(vararg entries: Pair<String, ByteArray>): Map<String, ByteArray> =
        mapOf("plugin.json" to manifest(), "skills/research/SKILL.md" to skill("research")) + entries

    private fun manifest(
        name: String = "research",
        extra: String = "",
    ): ByteArray {
        val fields =
            buildJsonObject {
                put("\$schema", PluginManifest.AGENT_PLUGINS_V1_SCHEMA)
                put("name", name)
            }.toString()
        return (if (extra.isEmpty()) fields else fields.dropLast(1) + "," + extra + "}").toByteArray()
    }

    private fun mcp(servers: String) =
        "{\"\$schema\":\"https://agent-plugins.org/schemas/1.0.0/mcp.schema.json\",\"mcpServers\":{$servers}}"
            .toByteArray()

    private fun http(url: String = "https://e.test/mcp") = "{\"type\":\"streamable-http\",\"url\":\"$url\"}"

    private fun skill(name: String) =
        "---\nname: $name\ndescription: Bounded test skill\n---\nRead a value.".toByteArray()
}
