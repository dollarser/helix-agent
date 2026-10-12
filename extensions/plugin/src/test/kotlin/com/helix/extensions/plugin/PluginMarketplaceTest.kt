package com.helix.extensions.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginMarketplaceTest {
    private fun bytes(vararg values: Pair<String, String>) = values.associate { it.first to it.second.toByteArray() }

    private val skill = "---\nname: guide\ndescription: Review the fixture\n---\n# Guide\nRead references/rules.md."

    private fun source(index: String = ".claude-plugin/marketplace.json") =
        bytes(
            index to """{"name":"fixtures","plugins":[
            {"name":"review","source":"./plugins/review"}]}""",
            "plugins/review/.claude-plugin/plugin.json" to """{
            "name":"review","version":"1.0","skills":["./extras/"]}""",
            "plugins/review/extras/guide/SKILL.md" to skill,
            "plugins/review/extras/guide/references/rules.md" to "Check the result; never send messages.",
            "plugins/review/.mcp.json" to """{"mcpServers":{"docs":{"url":"https://example.invalid/mcp"}}}""",
        )

    @Test fun formatsPreserveCandidateIdentityResourcesAndEndpointWithoutInstalling() {
        PluginMarketplaceReader.INDEX_PATHS.forEach { index ->
            val parsed = PluginMarketplaceReader().parse(source(index))
            assertEquals("fixtures", parsed.name)
            val entry = parsed.entries.single()
            assertNull(entry.issue)
            val bundle = requireNotNull(entry.bundle)
            assertEquals("review", bundle.name)
            assertEquals("1.0", entry.version)
            assertEquals("docs", bundle.endpoints.single().name)
            assertNotNull(bundle.skills.single().files["references/rules.md"])
            assertThrows(IllegalArgumentException::class.java) { PluginPackageReader().parse(source(index)) }
        }
    }

    @Test fun remoteSourcesRemainCandidatesAndInvalidSiblingDoesNotHideUsablePlugin() {
        val files = source().toMutableMap()
        files[".claude-plugin/marketplace.json"] =
            """{"name":"fixtures","plugins":[
          {"name":"review","source":"./plugins/review"},
          {"name":"remote","source":{"source":"github","repo":"example/remote"}},
          {"name":"broken","source":"./plugins/broken"}] }""".toByteArray()
        files["plugins/broken/.claude-plugin/plugin.json"] = "not JSON".toByteArray()
        val entries = PluginMarketplaceReader().parse(files).entries
        assertNotNull(entries[0].bundle)
        assertEquals("MARKETPLACE_SOURCE_NOT_BUNDLED", entries[1].issue)
        assertEquals("MARKETPLACE_PLUGIN_INVALID", entries[2].issue)
        assertTrue(entries.drop(1).all { it.bundle == null })
    }

    @Test fun unsafeAndAmbiguousCatalogsAreRejected() {
        val reader = PluginMarketplaceReader()
        val files = source().toMutableMap()
        files[".claude-plugin/marketplace.json"] =
            """{"name":"fixtures","plugins":[{"name":"review","source":"./../escape"}]}""".toByteArray()
        assertThrows(IllegalArgumentException::class.java) { reader.parse(files) }
        val ambiguous = source().toMutableMap()
        ambiguous[".codex-plugin/marketplace.json"] = """{"name":"other","plugins":[]}""".toByteArray()
        assertThrows(IllegalArgumentException::class.java) { reader.parse(ambiguous) }
        assertThrows(IllegalArgumentException::class.java) {
            reader.readJson("""{"name":"fixtures","plugins":[{"name":"x"},{"name":"x"}]}""".toByteArray())
        }
    }

    @Test fun mismatchedPackageNameAndMissingContentsDoNotBecomeInstalledCandidates() {
        val files = source().toMutableMap()
        files["plugins/review/.claude-plugin/plugin.json"] = """{"name":"another"}""".toByteArray()
        assertEquals(
            "MARKETPLACE_PLUGIN_NAME_MISMATCH",
            PluginMarketplaceReader()
                .parse(files)
                .entries
                .single()
                .issue,
        )
        val metadata =
            PluginMarketplaceReader().readJson(
                """{"name":"fixtures","plugins":[{"name":"review","source":"./plugins/review"}]}""".toByteArray(),
            )
        assertNull(metadata.entries.single().bundle)
        assertEquals("MARKETPLACE_SOURCE_MISSING", metadata.entries.single().issue)
    }

    @Test fun externalArrayComponentsMergeInOrderWithoutImportingCredentialsOrHooks() {
        for (format in listOf("codex", "claude", "codebuddy", "workbuddy")) {
            val parsed =
                PluginPackageReader().parse(
                    bytes(
                        ".$format-plugin/plugin.json" to """{"name":"review","skills":["./extras"],
                   "mcpServers":["./.mcp.json",{"docs":{"url":"https://new.invalid/mcp",
                   "headers":{"Authorization":"PRIVATE"}}}],
                   "hooks":{"SessionStart":[{"command":"never run"}]},"ui":{}}""",
                        ".mcp.json" to """{"docs":{"url":"https://old.invalid/mcp"}}""",
                        "extras/guide/SKILL.md" to skill,
                    ),
                )
            assertEquals("https://new.invalid/mcp", parsed.endpoints.single().url)
            assertTrue(parsed.endpoints.single().needsCredential)
            assertEquals("guide", parsed.skills.single().directory)
            assertTrue(parsed.diagnostics.contains("UNSUPPORTED_HOOKS"))
            assertTrue(parsed.diagnostics.contains("UNSUPPORTED_UI"))
            assertTrue(parsed.diagnostics.none { "PRIVATE" in it || "never run" in it })
        }
    }

    @Test fun rootSkillsPreserveReferencesWithoutCopyingMcpCredentials() {
        val bundle =
            PluginPackageReader().parse(
                bytes(
                    ".claude-plugin/plugin.json" to """{"name":"guide","skills":"./"}""",
                    "SKILL.md" to skill,
                    "references/rules.md" to "Check outcomes.",
                    ".mcp.json" to """{"docs":{"url":"https://example.invalid/mcp",
              "headers":{"Authorization":"PRIVATE"}}}""",
                ),
            )
        assertTrue(
            bundle.skills
                .single()
                .files
                .containsKey("references/rules.md"),
        )
        assertTrue(
            !bundle.skills
                .single()
                .files
                .containsKey(".mcp.json"),
        )
        assertTrue(
            !bundle.skills
                .single()
                .files
                .containsKey(".claude-plugin/plugin.json"),
        )
        assertTrue(bundle.endpoints.single().needsCredential)
        assertTrue(bundle.diagnostics.contains("SKILL_CREDENTIAL_CONFIG_OMITTED:guide"))
    }
}
